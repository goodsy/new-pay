package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.EntitlementLeaseUseCase
import com.paycore.master.merchant.application.provided.EntitlementPropagationQuery
import com.paycore.master.merchant.application.provided.EntitlementRelayUseCase
import com.paycore.master.merchant.application.provided.MerchantPropagationView
import com.paycore.master.merchant.application.provided.PropagationOverview
import com.paycore.master.merchant.application.provided.RelayReport
import com.paycore.master.merchant.application.provided.RenewalReport
import com.paycore.master.merchant.application.required.DispatchOutcome
import com.paycore.master.merchant.application.required.EntitlementDispatcher
import com.paycore.master.merchant.application.required.EntitlementIssueStore
import com.paycore.master.merchant.application.required.EntitlementPropagationReader
import com.paycore.master.merchant.application.required.EntitlementRelayStore
import com.paycore.master.merchant.application.required.EntitlementTiming
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.MerchantStore
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.RelayEnvelope
import com.paycore.master.merchant.application.required.RelayMarkResult
import com.paycore.master.merchant.application.required.SnapshotAck
import com.paycore.master.merchant.domain.ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION
import com.paycore.master.merchant.domain.MerchantId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ThreadLocalRandom

/**
 * Outbox Relay(MASTER-SNAPSHOT-001 명세 §4.7). 한 회차:
 *
 * ```
 * TX1(claim, FOR UPDATE SKIP LOCKED): ENTITLEMENT_SNAPSHOT·PENDING·next_attempt_at ≤ now·claim 없음/만료 → priority DESC, id 순 → claim 표시 → COMMIT
 * (TX 밖) Dispatcher(HTTP) — DB 잠금·TX 없음
 * TX2(행마다): 2xx → PUBLISHED + ACK / 재시도 가능 → attempt+1, next_attempt_at = now + backoff, claim 해제 / 4xx → QUARANTINED
 * ```
 * 재시도 상한 없음(차단 Snapshot을 포기하지 않는다 — 대기 연령 지표로 경보). Relay crash 뒤에는 claim 만료 후 다른 인스턴스가 같은 eventId로
 * 다시 보내고 PAYMENT는 DUPLICATE로 응답한다(at-least-once, Inbox가 멱등).
 */
@Service
class EntitlementRelayService(
    transactionManager: PlatformTransactionManager,
    private val relay: EntitlementRelayStore,
    private val dispatcher: EntitlementDispatcher,
    private val timing: EntitlementTiming,
    private val clock: MasterClock,
    private val metrics: MasterOperationalMetrics,
    ids: MasterIdGenerator,
) : EntitlementRelayUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(EntitlementRelayService::class.java)

    /** 이 프로세스의 Relay 소유자 이름(claim_owner). 인스턴스마다 다르다. */
    val owner: String = "relay-" + ids.newUuid()

    override fun relayOnce(): RelayReport {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val claimed = transactions.execute(::isRetryable) {
            relay.claim(owner, now, now.plus(timing.claimLease), timing.relayBatchSize)
        }
        var acknowledged = 0
        var retried = 0
        var quarantined = 0
        var lost = 0
        var failed = 0
        for (envelope in claimed) {
            // review n-6: 한 행의 예상 밖 예외가 같은 배치의 나머지를 claim 만료까지 묶지 않게 행마다 격리한다(그 행은 claim 만료 뒤 다시 집힌다).
            val outcome: DispatchOutcome
            val mark: RelayMarkResult
            try {
                outcome = dispatcher.dispatch(envelope)
                val at = clock.now().truncatedTo(ChronoUnit.MILLIS)
                mark = transactions.execute(::isRetryable) {
                when (outcome) {
                    is DispatchOutcome.Acknowledged -> relay.markPublished(
                        envelope.eventId, owner, SnapshotAck(outcome.consumer, outcome.outcome, outcome.appliedRevision, at, outcome.originalOutcome),
                    )
                    is DispatchOutcome.Retryable -> relay.markRetry(envelope.eventId, owner, at.plus(backoff(envelope.attemptCount)), outcome.code)
                    is DispatchOutcome.Rejected -> relay.markQuarantined(envelope.eventId, owner, outcome.code)
                }
                }
            } catch (e: RuntimeException) {
                failed++
                metrics.relayResult("FAILED")
                log.error("entitlement snapshot relay row failed eventId={} failure={}", envelope.eventId, e::class.simpleName)
                continue
            }
            val result = when {
                mark == RelayMarkResult.CLAIM_LOST -> "CLAIM_LOST".also { lost++ }
                outcome is DispatchOutcome.Acknowledged -> "ACKNOWLEDGED".also { acknowledged++ }
                outcome is DispatchOutcome.Retryable -> "RETRY".also { retried++ }
                else -> "QUARANTINED".also { quarantined++ }
            }
            metrics.relayResult(result)
            when (outcome) {
                is DispatchOutcome.Rejected -> log.error(
                    "entitlement snapshot quarantined eventId={} aggregateId={} revision={} failure={}",
                    envelope.eventId, envelope.aggregateId, envelope.aggregateVersion, outcome.code,
                )
                is DispatchOutcome.Retryable -> log.warn(
                    "entitlement snapshot delivery retry eventId={} revision={} attempt={} failure={}",
                    envelope.eventId, envelope.aggregateVersion, envelope.attemptCount + 1, outcome.code,
                )
                is DispatchOutcome.Acknowledged -> Unit
            }
        }
        return RelayReport(claimed.size, acknowledged, retried, quarantined, lost, failed)
    }

    /** 지수 backoff(attempt 0 → initial, 이후 ×2, 상한 max) + 선택 jitter(설정 비율 이내). 업무 정책이 아니라 전달 기술 설정이다. */
    internal fun backoff(attemptCount: Int): Duration {
        val initial = timing.backoffInitial.toMillis()
        val max = timing.backoffMax.toMillis()
        val shift = attemptCount.coerceIn(0, MAX_SHIFT)
        val base = (initial shl shift).coerceAtMost(max).coerceAtLeast(initial)
        val ratio = timing.backoffJitterRatio
        val jitter = if (ratio > 0.0) (base * ratio * ThreadLocalRandom.current().nextDouble()).toLong() else 0L
        return Duration.ofMillis((base + jitter).coerceAtMost(max.coerceAtLeast(base)))
    }

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MAX_SHIFT = 20
    }
}

/**
 * lease 갱신(명세 §3.3, R4). 후보(최근 발행 lease가 now + renewBefore 전에 끝나는 Merchant)를 잠금 없이 고른 뒤 Merchant마다 별도 TX에서
 * merchant 행을 잠그고 최근 발행을 다시 확인한다 — 여러 인스턴스가 동시에 돌아도 같은 Merchant의 갱신은 하나만 발행된다(뒤 TX는 새 lease를 보고
 * 건너뛴다, effect_key·issue PK가 안전망). 갱신 내용은 언제나 현재 MASTER 상태다(과거 허용을 되살리지 않는다).
 */
@Service
class EntitlementLeaseService(
    transactionManager: PlatformTransactionManager,
    private val merchants: MerchantStore,
    private val issues: EntitlementIssueStore,
    private val publisher: EntitlementSnapshotPublisher,
    private val timing: EntitlementTiming,
    private val clock: MasterClock,
    private val ids: MasterIdGenerator,
    private val metrics: MasterOperationalMetrics,
) : EntitlementLeaseUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(EntitlementLeaseService::class.java)

    override fun renewDueLeases(): RenewalReport {
        val scanAt = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val candidates = transactions.execute(::isRetryable) { issues.merchantsDueForRenewal(scanAt.plus(timing.renewBefore), SCAN_LIMIT) }
        var renewed = 0
        for (merchantId in candidates) {
            // 한 Merchant의 실패(결함·일시 장애)가 다른 Merchant의 갱신을 막지 않는다. 그 Merchant는 다음 회차에 다시 후보가 되고, 끝내 갱신되지
            // 않으면 PAYMENT lease 만료로 fail-closed가 된다.
            val ok = try {
                transactions.execute(::isRetryable) { renew(merchantId) }
            } catch (e: RuntimeException) {
                log.error("entitlement lease renewal failed merchantId={} failure={}", merchantId.value, e::class.simpleName)
                false
            }
            if (ok) renewed++
        }
        return RenewalReport(candidates.size, renewed, candidates.size - renewed)
    }

    private fun renew(merchantId: MerchantId): Boolean {
        val locked = merchants.lock(merchantId) ?: return false
        val merchant = locked.value
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val latest = issues.latest(merchantId)
        if (latest != null && !latest.leaseUntil.isBefore(now.plus(timing.renewBefore))) return false
        val correlation = "lease-renewal-" + ids.newUuid()
        if (latest != null && latest.schemaVersion < ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION) {
            // MASTER-SNAPSHOT-002 §5.2 스키마 승격: 같은 revision을 새 버전 내용으로 다시 내면 PAYMENT가 같은 revision·다른 hash(INTEGRITY_CONFLICT)로
            // 막는다. 그래서 같은 merchant 잠금 TX에서 revision을 올려 변경 발행(HIGH, leaseSeq 0)으로 낸다 — 정상 경로를 차단으로 만들지 않는다.
            merchant.recordEntitlementChange()
            merchants.update(merchant, locked.version)
            val published = publisher.publishChange(merchant, now, correlation, correlation) != null
            // 지표·로그는 발행 기록이 같은 TX에 써진 뒤에만(그 전 단계 실패는 rollback되고 승격으로 세지 않는다 — review round1 m-1).
            metrics.schemaPromotion()
            log.info(
                "entitlement snapshot schema promotion merchantId={} fromSchema={} toSchema={} revision={}",
                merchantId.value, latest.schemaVersion, ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION, merchant.entitlementRevision,
            )
            return published
        }
        return publisher.publishRenewal(merchant, latest, now, correlation, correlation) != null
    }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict ||
            (e is PersistenceFailure.UniqueConflict && e.constraint.businessKey)

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** 한 회차에 고르는 Merchant 수 상한(기술 설정 — 나머지는 다음 회차). */
        const val SCAN_LIMIT = 500
    }
}

/** 전파 조회 use case(jOOQ 읽기 Port에 위임, 시각은 서버 clock). */
@Service
class EntitlementPropagationQueryService(
    private val reader: EntitlementPropagationReader,
    private val clock: MasterClock,
) : EntitlementPropagationQuery {
    override fun merchant(merchantId: MerchantId): MerchantPropagationView? = reader.merchant(merchantId, now())

    override fun overview(): PropagationOverview = reader.overview(now())

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)
}

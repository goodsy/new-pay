package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.EntitlementLeaseUseCase
import com.paycore.master.merchant.application.provided.RenewalReport
import com.paycore.master.merchant.application.required.EntitlementIssueStore
import com.paycore.master.merchant.application.required.EntitlementTiming
import com.paycore.master.merchant.application.required.MasterClock
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.MerchantRepository
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.domain.ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION
import com.paycore.master.merchant.domain.MerchantId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * lease 갱신(명세 §3.3, R4). 후보(최근 발행 lease가 now + renewBefore 전에 끝나는 Merchant)를 잠금 없이 고른 뒤 Merchant마다 별도 TX에서
 * merchant 행을 잠그고 최근 발행을 다시 확인한다 — 여러 인스턴스가 동시에 돌아도 같은 Merchant의 갱신은 하나만 발행된다(뒤 TX는 새 lease를 보고
 * 건너뛴다, effect_key·issue PK가 안전망). 갱신 내용은 언제나 현재 MASTER 상태다(과거 허용을 되살리지 않는다).
 */
@Service
class EntitlementLeaseService(
    transactionManager: PlatformTransactionManager,
    private val merchants: MerchantRepository,
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

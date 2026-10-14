package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.EligibilityDecision
import com.paycore.payment.entitlement.application.provided.EligibilityRefusalReason
import com.paycore.payment.entitlement.application.provided.EligibilityRequest
import com.paycore.payment.entitlement.application.provided.EligibilityUnavailableCause
import com.paycore.payment.entitlement.application.provided.EntitlementEvaluationQuery
import com.paycore.payment.entitlement.application.provided.EntitlementFreshnessQuery
import com.paycore.payment.entitlement.application.provided.FreshnessOverview
import com.paycore.payment.entitlement.application.provided.InboundSnapshotEnvelope
import com.paycore.payment.entitlement.application.provided.ReceiveEntitlementSnapshotUseCase
import com.paycore.payment.entitlement.application.provided.ReceiveFailure
import com.paycore.payment.entitlement.application.provided.ReceiveResult
import com.paycore.payment.entitlement.application.required.EntitlementClock
import com.paycore.payment.entitlement.application.required.EntitlementFreshnessReader
import com.paycore.payment.entitlement.application.required.EntitlementMetrics
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.application.required.EntitlementTiming
import com.paycore.payment.entitlement.application.required.InboxRecord
import com.paycore.payment.entitlement.domain.EligibilityQuestion
import com.paycore.payment.entitlement.domain.EntitlementDecision
import com.paycore.payment.entitlement.domain.EntitlementEvaluator
import com.paycore.payment.entitlement.domain.InboxOutcome
import com.paycore.payment.entitlement.domain.RefusalReason
import com.paycore.payment.entitlement.domain.SUPPORTED_SNAPSHOT_SCHEMA_VERSION
import com.paycore.payment.entitlement.domain.SnapshotApplicationRule
import com.paycore.payment.entitlement.domain.UnavailableReason
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Inbox 수신(MASTER-SNAPSHOT-001 명세 §5.1, A03). PAYMENT 로컬 TX(READ COMMITTED) 하나에서:
 * ```
 * head IODKU(없으면 revision 0) → head FOR UPDATE → inbox (consumer, eventId) 조회
 *   있으면: 같은 원문이면 DUPLICATE(저장 outcome 대신 현재 head revision 응답), 다른 원문이면 EventIdConflict(계약 오류)
 *   없으면: 적용 규칙 → inbox INSERT → (APPLIED) revision별 내용 + 새 MID 소속 → head UPDATE
 * COMMIT → 결과(ACK)
 * ```
 * 같은 Merchant의 이벤트는 head 잠금으로 직렬화되므로 조회 뒤 INSERT가 경쟁하지 않는다(다른 Merchant가 같은 eventId를 쓰는 비정상 경합은
 * UNIQUE(consumer, event_id) 충돌 → 새 TX에서 재판정으로 수렴). 교착·충돌은 새 TX로 같은 의도를 다시 실행한다.
 */
@Service
class EntitlementInboxService(
    transactionManager: PlatformTransactionManager,
    private val store: EntitlementProjectionStore,
    private val clock: EntitlementClock,
    private val metrics: EntitlementMetrics,
) : ReceiveEntitlementSnapshotUseCase {

    private val transactions = EntitlementLocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(EntitlementInboxService::class.java)

    override fun receive(envelope: InboundSnapshotEnvelope): ReceiveResult {
        val result = try {
            transactions.execute(::isRetryable) { once(envelope) }
        } catch (e: EntitlementCommitOutcomeUnknownException) {
            throw ReceiveFailure("commit outcome unknown", e)
        } catch (e: EntitlementPersistenceFailure) {
            throw ReceiveFailure("temporarily unavailable", e)
        } catch (e: CannotCreateTransactionException) {
            throw ReceiveFailure("temporarily unavailable", e)
        } catch (e: UnexpectedRollbackException) {
            throw ReceiveFailure("temporarily unavailable", e)
        }
        val s = envelope.snapshot
        when (result) {
            is ReceiveResult.Stored -> {
                metrics.inboxOutcome(result.outcome)
                when (result.outcome) {
                    InboxOutcome.INTEGRITY_CONFLICT -> log.error(
                        "entitlement snapshot integrity conflict eventId={} merchantId={} revision={} (same revision, different content — head blocked)",
                        s.eventId, s.merchantId, s.revision,
                    )
                    InboxOutcome.UNSUPPORTED -> log.warn(
                        "entitlement snapshot schema unsupported eventId={} merchantId={} revision={} schemaVersion={} appliedRevision={}",
                        s.eventId, s.merchantId, s.revision, s.schemaVersion, result.appliedRevision,
                    )
                    else -> log.info(
                        "entitlement snapshot received eventId={} merchantId={} revision={} outcome={} appliedRevision={}",
                        s.eventId, s.merchantId, s.revision, result.outcome, result.appliedRevision,
                    )
                }
            }
            ReceiveResult.EventIdConflict -> {
                metrics.inboxRejected("EVENT_ID_CONFLICT")
                log.error("entitlement snapshot eventId reused with a different body eventId={} merchantId={}", s.eventId, s.merchantId)
            }
        }
        return result
    }

    private fun once(envelope: InboundSnapshotEnvelope): ReceiveResult {
        val s = envelope.snapshot
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val head = store.lockHeadForUpdate(s.merchantId)
        store.findInbox(CONSUMER, s.eventId)?.let { stored ->
            return if (stored.payloadSha256 == envelope.rawSha256) {
                ReceiveResult.Stored(CONSUMER, InboxOutcome.DUPLICATE, head.revision, stored.outcome)
            } else {
                ReceiveResult.EventIdConflict
            }
        }
        val decision = SnapshotApplicationRule.decide(head, s)
        val after = decision.newHead ?: head
        store.insertInbox(
            InboxRecord(
                consumer = CONSUMER, eventId = s.eventId, eventType = envelope.eventType, schemaVersion = s.schemaVersion, aggregateId = s.merchantId,
                aggregateVersion = s.revision, outcome = decision.outcome, headRevisionAfter = after.revision, payload = envelope.rawBody,
                payloadSha256 = envelope.rawSha256, receivedAt = now,
            ),
        )
        decision.appliedContent?.let { content ->
            store.insertSnapshot(content, s.schemaVersion)
            store.recordMidOwners(s.merchantId, content.accounts.map { it.mid }, now)
        }
        decision.newHead?.let { store.updateHead(it, now) }
        return ReceiveResult.Stored(CONSUMER, decision.outcome, after.revision)
    }

    private fun isRetryable(e: RuntimeException): Boolean =
        e is EntitlementPersistenceFailure.Deadlock || e is EntitlementPersistenceFailure.OptimisticConflict ||
            e is EntitlementPersistenceFailure.UniqueConflict

    companion object {
        /** 이 수신자의 이름(Inbox consumer, ACK consumer). */
        const val CONSUMER = "payment-api"
        private const val MAX_ATTEMPTS = 3
    }
}

/**
 * 결제 생성 판정(명세 §5.3, §6). 호출자 TX에 참여(MANDATORY)해 MID 소속(비잠금) → head `FOR SHARE` → revision별 내용(불변 행) 순으로 읽고 domain
 * [EntitlementEvaluator]로 판정한다. 쓰기·외부 호출·캐시 없음. 거절 세부(SITE_REVIEW 등)·운영 중지 범위(BUSINESS/MERCHANT/MID)는 구조화 로그에만
 * 남긴다(응답 계약·metric label 아님, D-MD-019·D-MS-002).
 */
@Service
class EntitlementEvaluationService(
    private val store: EntitlementProjectionStore,
    private val clock: EntitlementClock,
    private val timing: EntitlementTiming,
    private val metrics: EntitlementMetrics,
) : EntitlementEvaluationQuery {

    private val log = LoggerFactory.getLogger(EntitlementEvaluationService::class.java)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun evaluate(request: EligibilityRequest, evaluatedAt: Instant): EligibilityDecision {
        val question = EligibilityQuestion(request.mid, request.siteId, request.paymentMethod, request.currency)
        val merchantId = store.findMidOwner(request.mid)
        val head = merchantId?.let { store.lockHeadForShare(it) }
        val usable = head != null && !head.isEmpty && head.blocked == null && head.schemaVersion == SUPPORTED_SNAPSHOT_SCHEMA_VERSION
        val content = if (usable) store.findSnapshot(checkNotNull(merchantId), checkNotNull(head).revision) else null
        val decision = EntitlementEvaluator.evaluate(head, content, question, evaluatedAt, clock.now(), timing.clockSkewMargin)
        return publish(decision, request)
    }

    private fun publish(decision: EntitlementDecision, request: EligibilityRequest): EligibilityDecision = when (decision) {
        is EntitlementDecision.Allowed -> {
            metrics.eligibility("ELIGIBLE", null)
            EligibilityDecision.Allowed(decision.snapshotVersion, decision.currency, decision.minorUnit, decision.validUntil)
        }
        is EntitlementDecision.NotFoundOrForbidden -> {
            metrics.eligibility("NOT_FOUND_OR_FORBIDDEN", null)
            log.info("entitlement eligibility refused result=NOT_FOUND_OR_FORBIDDEN detail={} mid={} siteId={}", decision.detail, request.mid, request.siteId)
            EligibilityDecision.NotFoundOrForbidden
        }
        is EntitlementDecision.Refused -> {
            metrics.eligibility("NOT_ELIGIBLE", decision.reason.name)
            log.info(
                "entitlement eligibility refused result=NOT_ELIGIBLE reason={} detail={} suspendedScope={} mid={} siteId={}",
                decision.reason, decision.detail, decision.suspendedScope, request.mid, request.siteId,
            )
            EligibilityDecision.Refused(
                when (decision.reason) {
                    RefusalReason.MERCHANT_SUSPENDED -> EligibilityRefusalReason.MERCHANT_SUSPENDED
                    RefusalReason.CONTRACT_NOT_EFFECTIVE -> EligibilityRefusalReason.CONTRACT_NOT_EFFECTIVE
                    RefusalReason.METHOD_NOT_ENABLED -> EligibilityRefusalReason.METHOD_NOT_ENABLED
                    RefusalReason.CURRENCY_NOT_ENABLED -> EligibilityRefusalReason.CURRENCY_NOT_ENABLED
                },
            )
        }
        is EntitlementDecision.Unavailable -> {
            metrics.eligibility("UNAVAILABLE", decision.reason.name)
            log.warn("entitlement eligibility unavailable reason={} mid={}", decision.reason, request.mid)
            EligibilityDecision.Unavailable(
                when (decision.reason) {
                    UnavailableReason.SNAPSHOT_MISSING -> EligibilityUnavailableCause.SNAPSHOT_MISSING
                    UnavailableReason.SNAPSHOT_EXPIRED -> EligibilityUnavailableCause.SNAPSHOT_EXPIRED
                    UnavailableReason.VERSION_UNSUPPORTED -> EligibilityUnavailableCause.VERSION_UNSUPPORTED
                },
            )
        }
    }
}

/** 신선도 조회 use case(jOOQ 읽기 Port에 위임, 시각은 서버 clock). */
@Service
class EntitlementFreshnessService(private val reader: EntitlementFreshnessReader, private val clock: EntitlementClock) : EntitlementFreshnessQuery {
    override fun overview(): FreshnessOverview = reader.overview(clock.now().truncatedTo(ChronoUnit.MILLIS))
}

/** UTC 시스템 시계(JDK Clock만). */
@Component
class SystemEntitlementClock : EntitlementClock {
    private val clock: Clock = Clock.systemUTC()

    override fun now(): Instant = clock.instant()
}

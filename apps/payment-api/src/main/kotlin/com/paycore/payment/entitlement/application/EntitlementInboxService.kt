package com.paycore.payment.entitlement.application

import com.paycore.payment.entitlement.application.provided.InboundSnapshotEnvelope
import com.paycore.payment.entitlement.application.provided.ReceiveEntitlementSnapshotUseCase
import com.paycore.payment.entitlement.application.provided.ReceiveFailure
import com.paycore.payment.entitlement.application.provided.ReceiveResult
import com.paycore.payment.entitlement.application.required.EntitlementClock
import com.paycore.payment.entitlement.application.required.EntitlementMetrics
import com.paycore.payment.entitlement.application.required.EntitlementPersistenceFailure
import com.paycore.payment.entitlement.application.required.EntitlementProjectionStore
import com.paycore.payment.entitlement.application.required.InboxRecord
import com.paycore.payment.entitlement.domain.InboxOutcome
import com.paycore.payment.entitlement.domain.SnapshotApplicationRule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
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
                        EntitlementLogValues.safe(s.eventId), EntitlementLogValues.safe(s.merchantId), s.revision,
                    )
                    InboxOutcome.UNSUPPORTED -> log.warn(
                        "entitlement snapshot schema unsupported eventId={} merchantId={} revision={} schemaVersion={} appliedRevision={}",
                        EntitlementLogValues.safe(s.eventId), EntitlementLogValues.safe(s.merchantId), s.revision, s.schemaVersion, result.appliedRevision,
                    )
                    else -> log.info(
                        "entitlement snapshot received eventId={} merchantId={} revision={} outcome={} appliedRevision={}",
                        EntitlementLogValues.safe(s.eventId), EntitlementLogValues.safe(s.merchantId), s.revision, result.outcome, result.appliedRevision,
                    )
                }
            }
            ReceiveResult.EventIdConflict -> {
                metrics.inboxRejected("EVENT_ID_CONFLICT")
                log.error("entitlement snapshot eventId reused with a different body eventId={} merchantId={}", EntitlementLogValues.safe(s.eventId), EntitlementLogValues.safe(s.merchantId))
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

package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsCommand
import com.paycore.payment.payment.application.provided.FanOutMerchantNotificationsUseCase
import com.paycore.payment.payment.application.provided.MerchantNotificationFanoutReport
import com.paycore.payment.payment.application.required.MerchantCodePort
import com.paycore.payment.payment.application.required.MerchantNotificationFacts
import com.paycore.payment.payment.application.required.MerchantNotificationFanoutStore
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryPlan
import com.paycore.payment.payment.application.required.MerchantWebhookDeliveryRepository
import com.paycore.payment.payment.application.required.MerchantWebhookEndpointDirectory
import com.paycore.payment.payment.application.required.MerchantWebhookEnvelope
import com.paycore.payment.payment.application.required.MerchantWebhookEventRecord
import com.paycore.payment.payment.application.required.MerchantWebhookEventStore
import com.paycore.payment.payment.application.required.MerchantWebhookIdentifierGenerator
import com.paycore.payment.payment.application.required.OutboxNotificationMark
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PendingOutboxNotification
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.UnreadableNotificationSourceException
import com.paycore.payment.payment.domain.MerchantNotificationAnomalyException
import com.paycore.payment.payment.domain.MerchantNotificationDecision
import com.paycore.payment.payment.domain.MerchantNotificationPolicy
import com.paycore.payment.payment.domain.MerchantNotificationType
import com.paycore.payment.payment.domain.MerchantWebhookDelivery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Outbox → 가맹점 이벤트 fan-out(WEBHOOK-001 §5.0 rev 0.2 — H-3·m-3, WH-04·WH-10·WH-12·WH-18).
 *
 * ```
 * 후보(비잠금 읽기, Outbox id 순) → 행마다 LocalTransactions(READ COMMITTED) 한 TX:
 *   guard FOR UPDATE → 행 재읽기(아직 처리 대상인가) → 분류(MerchantNotificationPolicy)
 *   → NOTIFY: sequence +1 → 이벤트 INSERT → endpoint 있으면 전달 행 INSERT → Outbox 표시(PK UPDATE)
 *   → SUPPRESS / NOT_APPLICABLE: Outbox 표시만
 * 실패(이상 조합·해석 불가·사실 불일치·결함·저장 실패): 그 TX rollback → 새 TX로 그 행만 FANOUT_FAILED(비종결, 재시도 시각) → 같은 Payment의 뒤 행은 이번 회차에서 건너뜀
 * ```
 * 이상 조합은 절대 SUPPRESSED가 되지 않는다(fail-closed). 업무 TX를 바꾸지 않으며(WH-02) 외부 호출이 없다. 금융 상태를 바꾸지 않는다(D-WH-006).
 * MERCHANT-CODE-001 MC-4: 봉투에 그 MID 가맹점 코드(출력 전용, [MerchantCodePort] — 같은 PAYMENT 로컬 TX의 권리 투영 읽기)를 싣는다. 코드 없는 MID는 키 생략.
 */
@Service
class MerchantNotificationFanoutService(
    transactionManager: PlatformTransactionManager,
    private val store: MerchantNotificationFanoutStore,
    private val events: MerchantWebhookEventStore,
    private val deliveries: MerchantWebhookDeliveryRepository,
    private val endpoints: MerchantWebhookEndpointDirectory,
    private val ids: MerchantWebhookIdentifierGenerator,
    private val clock: PaymentClock,
    private val merchantCodes: MerchantCodePort,
) : FanOutMerchantNotificationsUseCase {

    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(MerchantNotificationFanoutService::class.java)

    private enum class Outcome { NOTIFIED, NO_ENDPOINT, SUPPRESSED, NOT_APPLICABLE, GONE, DEFERRED }

    override fun fanOut(command: FanOutMerchantNotificationsCommand): MerchantNotificationFanoutReport {
        val start = now()
        val candidates = store.candidates(start, command.batchSize)
        val blockedPayments = mutableSetOf<String>()
        val counts = mutableMapOf<Outcome, Int>()
        var failed = 0
        var deferred = 0
        for (candidate in candidates) {
            val key = candidate.paymentKey
            if (key != null && key in blockedPayments) {
                deferred++
                continue
            }
            try {
                val outcome = transactions.execute(::isRetryable) { processRow(candidate.outboxId, key) }
                if (outcome == Outcome.DEFERRED) {
                    // H-1: 앞 행이 아직 미종결(다른 인스턴스가 그 사이 실패로 커밋했을 수 있다) — 이 Payment의 뒤 행은 이번 회차에서 모두 보류.
                    deferred++
                    key?.let(blockedPayments::add)
                    continue
                }
                counts.merge(outcome, 1, Int::plus)
            } catch (e: RuntimeException) {
                failed++
                key?.let(blockedPayments::add)
                val code = failureCode(e)
                log.error("operation=MerchantNotificationFanout outboxId={} result=FANOUT_FAILED failure={}", candidate.outboxId, code)
                if (!markFailed(candidate.outboxId, code, command)) break
            }
        }
        return MerchantNotificationFanoutReport(
            claimed = candidates.size,
            notified = (counts[Outcome.NOTIFIED] ?: 0) + (counts[Outcome.NO_ENDPOINT] ?: 0),
            noEndpoint = counts[Outcome.NO_ENDPOINT] ?: 0,
            suppressed = counts[Outcome.SUPPRESSED] ?: 0,
            notApplicable = counts[Outcome.NOT_APPLICABLE] ?: 0,
            failed = failed,
            deferred = deferred,
        )
    }

    private fun processRow(outboxId: Long, paymentKey: String?): Outcome {
        store.lockGuard()
        if (paymentKey != null && store.hasEarlierOpenRow(outboxId, paymentKey)) return Outcome.DEFERRED
        val at = now()
        val row = store.load(outboxId, at) ?: return Outcome.GONE
        return when (val decision = MerchantNotificationPolicy.classify(row.eventType, row.subject?.payment?.status)) {
            MerchantNotificationDecision.NotApplicable -> {
                store.mark(outboxId, OutboxNotificationMark.NOT_APPLICABLE, at)
                Outcome.NOT_APPLICABLE
            }
            is MerchantNotificationDecision.Suppress -> {
                store.mark(outboxId, OutboxNotificationMark.SUPPRESSED, at)
                Outcome.SUPPRESSED
            }
            is MerchantNotificationDecision.Notify -> notify(row, decision.type, at)
        }
    }

    private fun notify(row: PendingOutboxNotification, type: MerchantNotificationType, at: Instant): Outcome {
        // 분류가 Notify면 주체·Payment 행이 있다(없으면 classify가 이상으로 던졌다).
        val subject = checkNotNull(row.subject)
        val payment = checkNotNull(subject.payment)
        if (!MerchantNotificationFacts.matches(type, subject.facts)) throw FactsMismatchException()
        val sequence = store.nextSequence(subject.paymentId)
        val eventId = ids.eventId(at)
        val endpoint = endpoints.find(subject.environment, subject.merchantAccountId)
        val envelope = MerchantWebhookEnvelope(
            eventId, type, subject.environment, subject.merchantAccountId, at, sequence, subject.paymentId, subject.merchantOrderId, payment, subject.facts,
            merchantCode = merchantCodes.merchantCodeOf(subject.merchantAccountId),
        )
        val plan = if (endpoint != null) MerchantWebhookDeliveryPlan.DELIVER else MerchantWebhookDeliveryPlan.NO_ENDPOINT
        events.append(MerchantWebhookEventRecord(envelope, row.sourceEventId, plan, at))
        if (endpoint != null) {
            deliveries.insertNew(MerchantWebhookDelivery.pending(ids.deliveryId(at), eventId, subject.environment, subject.merchantAccountId, at))
        }
        store.mark(row.outboxId, OutboxNotificationMark.NOTIFIED, at)
        return if (endpoint != null) Outcome.NOTIFIED else Outcome.NO_ENDPOINT
    }

    /** 실패 표시. 표시조차 실패하면(저장소 장애) 이번 회차를 멈춘다(false) — 행은 처리 대상으로 남는다. */
    private fun markFailed(outboxId: Long, code: String, command: FanOutMerchantNotificationsCommand): Boolean = try {
        transactions.execute(::isRetryable) {
            store.lockGuard()
            val at = now()
            store.markFailed(outboxId, code, at, at.plus(command.failedRetryInterval))
        }
        true
    } catch (e: RuntimeException) {
        log.error("operation=MerchantNotificationFanout outboxId={} result=MARK_FAILED_UNAVAILABLE failure={}", outboxId, e::class.simpleName)
        false
    }

    private fun failureCode(e: RuntimeException): String = when (e) {
        is MerchantNotificationAnomalyException -> "UNEXPECTED_COMBINATION"
        is UnreadableNotificationSourceException -> e.code
        is FactsMismatchException -> "FACTS_MISMATCH"
        is PersistenceFailure -> "STORE_" + e.failureKind.name
        is CommitOutcomeUnknownException -> "COMMIT_OUTCOME_UNKNOWN"
        else -> "DEFECT"
    }

    private fun now(): Instant = clock.now().truncatedTo(ChronoUnit.MILLIS)

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.LockTimeout

    private class FactsMismatchException : RuntimeException("notification facts do not match the classified type")

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}

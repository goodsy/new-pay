package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.required.AuthorizationIdentifierGenerator
import com.paycore.payment.payment.application.required.CancelInquiryCallResult
import com.paycore.payment.payment.application.required.CancellationCallResult
import com.paycore.payment.payment.application.required.CancellationRecoveryScheduleStore
import com.paycore.payment.payment.application.required.CancellationTimings
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.IntegrationEventIdGenerator
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.OutboxStore
import com.paycore.payment.payment.application.required.PaymentAuthorizationRepository
import com.paycore.payment.payment.application.required.PaymentCancellationRepository
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.ReturnBalanceRepository
import com.paycore.payment.payment.domain.AbandonCause
import com.paycore.payment.payment.domain.AttemptState
import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelObservation
import com.paycore.payment.payment.domain.CancelResolutionOutcome
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.CancellationResolution
import com.paycore.payment.payment.domain.CancellationStatus
import com.paycore.payment.payment.domain.IncidentReason
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.ReturnBalance
import com.paycore.payment.payment.domain.ReturnExcess
import com.paycore.payment.payment.domain.UnknownCause
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 기관 취소 결과 반영 TX2(CANCEL-001 §4.6) — 취소 요청의 응답 반영과 회복 Worker의 조회 반영·lease 표시·fencing이 **같은 단계**를 쓴다. 한 PAYMENT 로컬 TX, 잠금 순서 §4.7:
 * control → payment → payment_authorization → return_balance → payment_cancellation(→ attempt) → 회복 일정. 관찰 INSERT·Aggregate 갱신·CANCEL 거래·Outbox가 같은 TX다(K-13).
 * 사건 이벤트는 **사건마다** 하나(D-CANCEL1-002 — 새 hold 여부와 무관, effect_key의 사건 순번 = 이 TX 뒤 `incident_count`). 교착·version 충돌은 새 TX로 다시 판정하고, 그 밖 실패는
 * 호출자에게 던진다(취소 요청은 202 — 회복이 원 취소 요청 ID 조회로 정리). Payment 상태·주문 승인 이력·일 한도는 바꾸지 않는다(K-12).
 */
@Service
class CancellationResultService(
    transactionManager: PlatformTransactionManager,
    private val controls: OrderPaymentControlRepository,
    private val payments: PaymentRepository,
    private val authorizations: PaymentAuthorizationRepository,
    private val balances: ReturnBalanceRepository,
    private val cancellations: PaymentCancellationRepository,
    private val schedules: CancellationRecoveryScheduleStore,
    private val outbox: OutboxStore,
    private val views: CancellationViewReader,
    private val eventIds: IntegrationEventIdGenerator,
    private val ids: AuthorizationIdentifierGenerator,
    private val timings: CancellationTimings,
    private val clock: PaymentClock,
) {
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)

    /** 기관 취소 호출 결과(TX2): 관찰이면 판정표, connect 단계 실패면 전송 전 포기(CONNECT_FAILED), 쓰기 뒤 실패면 결과 불명확 표시. */
    fun applyCall(cancellationId: CancellationId, attemptId: CancelAttemptId, result: CancellationCallResult, causation: EventCausation): CancelResolutionOutcome =
        apply(cancellationId, causation) { c ->
            when (result) {
                is CancellationCallResult.Approved -> observe(c, result.observation)
                is CancellationCallResult.Declined -> observe(c, result.observation)
                is CancellationCallResult.Pending -> observe(c, result.observation)
                CancellationCallResult.NotSentConfirmed -> CancellationResolution.abandonBeforeDispatch(c.cancellation, c.balance, attemptId, AbandonCause.CONNECT_FAILED)
                is CancellationCallResult.OutcomeUnknown -> CancellationResolution.markOutcomeUnknown(c.cancellation, c.balance, attemptId, result.cause)
            }
        }

    /** 관찰 하나(응답·조회·통보·주입)를 반영한다. */
    fun applyObservation(cancellationId: CancellationId, observation: CancelObservation, causation: EventCausation = DEFAULT_CAUSATION): CancelResolutionOutcome =
        apply(cancellationId, causation) { c -> observe(c, observation) }

    /** 회복 조회 결과 하나(§7): 관찰 반영과 회복 일정의 조회 수 +1·다음 조회 시각을 **같은 TX**에 적는다. NOT_FOUND·PENDING은 관찰로만 남고 UNKNOWN 그대로다. */
    fun applyInquiry(cancellationId: CancellationId, attemptId: CancelAttemptId, result: CancelInquiryCallResult, causation: EventCausation): CancelResolutionOutcome =
        apply(cancellationId, causation, inquired = attemptId) { c ->
            when (result) {
                is CancelInquiryCallResult.Found -> observe(c, result.observation)
                is CancelInquiryCallResult.NotFound -> observe(c, result.observation)
                is CancelInquiryCallResult.NoEffectConfirmed -> observe(c, result.observation)
                is CancelInquiryCallResult.Unavailable -> CancelResolutionOutcome.StillUnknown
            }
        }

    /** 회복: dispatch lease 만료(전송 여부 불명 — "호출 안 함"으로 가정하지 않는다) → OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED). 이 TX에서 잠근 일정의 lease로 판정한다. */
    fun markLeaseExpired(cancellationId: CancellationId, attemptId: CancelAttemptId, causation: EventCausation): CancelResolutionOutcome =
        apply(cancellationId, causation) { c ->
            val lease = schedules.lock(attemptId)?.dispatchLeaseUntil
            if (lease == null || c.at.isBefore(lease)) CancelResolutionOutcome.NoChange
            else CancellationResolution.markOutcomeUnknown(c.cancellation, c.balance, attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED)
        }

    /** 회복: 전송되지 않은 PREPARED Attempt를 fencing으로 포기(같은 행 잠금·version — TXd와 먼저 커밋한 쪽만 이긴다, K7). */
    fun fence(cancellationId: CancellationId, attemptId: CancelAttemptId, causation: EventCausation): CancelResolutionOutcome =
        apply(cancellationId, causation) { c ->
            if (c.cancellation.attempt(attemptId).state != AttemptState.PREPARED) CancelResolutionOutcome.NoChange
            else CancellationResolution.abandonBeforeDispatch(c.cancellation, c.balance, attemptId, AbandonCause.FENCED_BY_RECOVERY)
        }

    private fun observe(c: Context, observation: CancelObservation): CancelResolutionOutcome {
        c.lastObservation = observation
        return CancellationResolution.applyObservation(c.control, c.payment, c.cancellation, c.balance, observation, ids.transactionId(c.at), c.at)
    }

    private class Context(
        val control: OrderPaymentControl, val payment: Payment, val authorization: PaymentAuthorization, val balance: ReturnBalance, val cancellation: PaymentCancellation,
        val at: Instant,
    ) {
        var lastObservation: CancelObservation? = null
    }

    private fun apply(
        cancellationId: CancellationId,
        causation: EventCausation,
        inquired: CancelAttemptId? = null,
        decide: (Context) -> CancelResolutionOutcome,
    ): CancelResolutionOutcome {
        // 잠금 순서(control부터)를 지키려고 주문 키를 먼저 비권위 조회로 찾는다(승인 TX2와 같은 방식). 판정은 아래 잠금 재판정이 한다.
        val anchor = views.find(cancellationId) ?: error("the cancellation is not stored")
        return transactions.execute(::isRetryable) { inTx(cancellationId, anchor.orderKey, anchor.paymentId, causation, inquired, decide) }
    }

    private fun inTx(
        cancellationId: CancellationId,
        orderKey: com.paycore.payment.payment.domain.OrderKey,
        paymentId: com.paycore.payment.payment.domain.PaymentId,
        causation: EventCausation,
        inquired: CancelAttemptId?,
        decide: (Context) -> CancelResolutionOutcome,
    ): CancelResolutionOutcome {
        val at = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val control = controls.lock(orderKey) ?: error("a stored cancellation has its order control")
        val payment = payments.lock(paymentId) ?: error("a stored cancellation has its payment")
        val authorization = authorizations.lockByPaymentId(paymentId) ?: error("a stored cancellation has its authorization")
        val balance = balances.lockByPaymentId(paymentId) ?: error("a stored cancellation has its return balance")
        val cancellation = cancellations.lock(cancellationId) ?: error("the cancellation is not stored")
        val holdsBefore = control.value.incidentHolds.size
        val c = Context(control.value, payment.value, authorization.value, balance.value, cancellation.value, at)
        val outcome = decide(c)
        controls.update(c.control, control.version, at)
        balances.update(c.balance, balance.version, at)
        val version = cancellations.update(c.cancellation, cancellation.version)
        publish(c, outcome, holdsBefore, version, causation)
        reschedule(c, at, inquired)
        return outcome
    }

    private fun publish(c: Context, outcome: CancelResolutionOutcome, holdsBefore: Int, version: Long, causation: EventCausation) {
        val x = c.cancellation
        when (outcome) {
            is CancelResolutionOutcome.Completed -> {
                val site = checkNotNull(payments.findCreationRecord(x.paymentId)).attributes.merchantSiteId.value
                val original = checkNotNull(c.authorization.transaction) { "a completed cancellation has its original authorization transaction" }
                outbox.append(CancellationEvents.cancelled(x, outcome, original, site, version, eventIds.newEventId(), causation))
            }
            is CancelResolutionOutcome.Failed -> outbox.append(CancellationEvents.failed(x, version, c.at, eventIds.newEventId(), causation))
            is CancelResolutionOutcome.Incident -> {
                // D-CANCEL1-002: 사건마다 이벤트 하나(새 hold가 없어도). 이번 TX에서 열린 hold가 있으면 그 occurrence, 아니면 이 Payment의 활성 사건 hold.
                val opened = c.control.incidentHolds.drop(holdsBefore).firstOrNull { it.paymentId == x.paymentId }
                val hold = opened ?: c.control.activeIncidentHolds.lastOrNull { it.paymentId == x.paymentId && it.reason == IncidentReason.CANCEL_RESULT_CONFLICT }
                // 검토 #3 n-4: 초과(ReturnExcess.EXCEEDED)일 때만 CRITICAL — 잔액 안의 늦은 성공(WITHIN)은 HIGH. 초과 금액은 이 Payment의 누적 초과(승인액을 넘은 반환 가능성).
                val exceeded = c.balance.reservationOf(x.id)?.excess == ReturnExcess.EXCEEDED
                val excessMinor = if (exceeded) c.balance.excessMinor else 0L
                outbox.append(CancellationEvents.incident(x, outcome, hold, c.lastObservation, exceeded, excessMinor, version, c.at, eventIds.newEventId(), causation))
            }
            else -> {}
        }
    }

    private fun reschedule(c: Context, at: Instant, inquired: CancelAttemptId?) {
        c.cancellation.attempts.forEach { attempt ->
            val schedule = schedules.lock(attempt.id) ?: return@forEach
            if (schedule.closedAt != null) return@forEach
            val counted = if (attempt.id == inquired) schedule.copy(inquiryCount = schedule.inquiryCount + 1) else schedule
            val due = at.plus(if (counted.escalatedAt != null) timings.slowInquiryInterval else timings.inquiryInterval)
            val next = when {
                // 결론난 취소(완료·실패)는 일정을 닫는다. 진행 중(사건 처리된 UNKNOWN 포함 — 출구는 RECON, §7.1)은 이관까지 조회를 계속한다.
                !c.cancellation.isInFlight() -> counted.copy(closedAt = at)
                attempt.state == AttemptState.OUTCOME_UNKNOWN || c.cancellation.status == CancellationStatus.UNKNOWN ->
                    if (attempt.id == inquired || counted.nextCheckAt.isAfter(due)) counted.copy(nextCheckAt = due) else counted
                else -> counted
            }
            if (next != schedule) schedules.update(next, at)
        }
    }

    private fun isRetryable(e: RuntimeException): Boolean = e is PersistenceFailure.Deadlock || e is PersistenceFailure.OptimisticConflict

    private companion object {
        const val MAX_ATTEMPTS = 3
        val DEFAULT_CAUSATION = EventCausation("cancellation-result", "cancellation-result")
    }
}

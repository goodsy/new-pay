package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.CancellationStatus.COMPLETED
import com.paycore.payment.payment.domain.CancellationStatus.FAILED
import com.paycore.payment.payment.domain.CancellationStatus.OPEN
import com.paycore.payment.payment.domain.CancellationStatus.UNKNOWN
import com.paycore.payment.payment.domain.InstitutionOutcome.APPROVED
import com.paycore.payment.payment.domain.InstitutionOutcome.DECLINED
import com.paycore.payment.payment.domain.InstitutionOutcome.NOT_FOUND
import com.paycore.payment.payment.domain.InstitutionOutcome.NO_EFFECT_CONFIRMED
import com.paycore.payment.payment.domain.InstitutionOutcome.PENDING
import java.time.Instant

/** 취소 결과 반영의 업무 효과(application이 Outbox·멱등 결과를 고른다 — 명세 §9.3). */
sealed interface CancelResolutionOutcome {
    /**
     * 취소 확정: CANCEL 거래 1건(D-AUTH-003) → PaymentCancelled. [completion]은 Payment 안 반환 순번·누적 반환액, [authorized]는 원 승인 Money,
     * [statusAfter]는 이 완료 뒤 반환 요약 — 누적이 승인액에 닿은 취소(FULLY_CANCELLED)가 D-CANCEL-009의 "전액 취소"다(계산은 SETTLEMENT).
     */
    data class Completed(
        val transaction: CancelTransaction,
        val completion: ReturnCompletion,
        val authorized: Money,
        val statusAfter: ReturnStatus,
    ) : CancelResolutionOutcome

    /** 무효과 확정(기관 거절·전송 전 실패·무효과) → 예약 해제 → PaymentCancelFailed. */
    data class Failed(val evidence: TerminationEvidence) : CancelResolutionOutcome

    /** 결과 불명확으로 바뀌었다(원 취소 Attempt 조회로 회복). */
    data object BecameUnknown : CancelResolutionOutcome

    /** 계속 불명확(NOT_FOUND·PENDING·UNKNOWN은 종료 근거가 아니다). */
    data object StillUnknown : CancelResolutionOutcome

    /** 효과 없음(중복 수신·확정 뒤 같은 결과·이미 사건 처리된 취소) — 관찰은 기록됐을 수 있다. */
    data object NoChange : CancelResolutionOutcome

    /** 취소 사건(D-CANCEL-006) → CancellationIncidentDetected. 자동 재취소 없음. */
    data class Incident(val reason: IncidentReason) : CancelResolutionOutcome
}

/**
 * 기관 취소 결과를 취소 operation·반환 잔액·주문 control에 한 연산으로 반영한다(CANCEL-CONTRACT-001 §4.6 판정표, TX2·회복 경로 공통, T5).
 * 먼저 온 결과나 마지막 결과를 무조건 정답으로 하지 않는다(허용 §5.2). 확정 금융 사실은 덮어쓰지 않는다.
 *
 * - 일치하는 확정 성공 → COMPLETED + CANCEL 거래 1건 + 예약 CONSUMED. 기관 확정 거절·무효과 확정 → FAILED + RELEASED.
 * - timeout·NOT_FOUND·PENDING·lease 만료는 종료 근거가 아니다 → UNKNOWN·RESERVED 유지(K-14).
 * - 상충(금액·통화·원 거래 불일치 성공, 보내지 않은 Attempt의 확정 결과, 같은 관찰 키·다른 내용) → 진행 중이면 UNKNOWN 유지·예약 유지, 완료면 상태 유지·두 번째
 *   거래 없음 + 이 Payment에 CANCEL_RESULT_CONFLICT hold(D-CANCEL-006). 사건 처리된 취소의 이후 결과는 기록만 한다(해소는 수동·2인·재대사 — D-AUTH-002 방식).
 * - 확정 거절한 취소에 늦은 성공 → 상태 유지·거래 없음 + 예약 FORCED 재기록(초과 표시) + hold(D-CANCEL-006).
 * hold는 이 Payment의 추가 가맹점 취소를 막을 뿐, 다른 취소 operation(운영자 보정)의 결과 반영은 막지 않는다. 일 한도·Payment 상태·주문 승인 이력은 바꾸지 않는다.
 */
object CancellationResolution {

    fun applyObservation(
        control: OrderPaymentControl,
        payment: Payment,
        cancellation: PaymentCancellation,
        balance: ReturnBalance,
        observation: CancelObservation,
        newTransactionId: PaymentTransactionId,
        at: Instant,
    ): CancelResolutionOutcome {
        requireSameOperation(control, payment, cancellation, balance)
        when (cancellation.recordObservation(observation)) {
            ObservationRecord.DUPLICATE -> return CancelResolutionOutcome.NoChange
            ObservationRecord.KEY_CONFLICT -> {
                val attempt = cancellation.attempt(observation.attemptId)
                if (cancellation.status == FAILED && observation.outcome == APPROVED) balance.reoccupyAfterLateCancel(cancellation.id)
                return conflict(control, payment, cancellation, attempt)
            }
            ObservationRecord.NEW -> {}
        }
        val attempt = cancellation.attempt(observation.attemptId)
        return when (cancellation.status) {
            OPEN, UNKNOWN -> applyToPending(control, payment, cancellation, balance, attempt, observation, newTransactionId, at)
            COMPLETED -> {
                val sameCancel = observation.outcome == APPROVED && observation.confirmed == checkNotNull(cancellation.transaction).terms
                if (sameCancel || informational(observation.outcome)) CancelResolutionOutcome.NoChange else conflict(control, payment, cancellation, attempt)
            }
            FAILED ->
                if (observation.outcome == APPROVED) {
                    balance.reoccupyAfterLateCancel(cancellation.id)
                    conflict(control, payment, cancellation, attempt)
                } else {
                    CancelResolutionOutcome.NoChange
                }
        }
    }

    /** 전송 전 포기(Attempt ABANDONED_BEFORE_DISPATCH)를 취소 FAILED(PRE_TRANSMISSION_FAILURE_CONFIRMED)와 예약 해제에 반영한다. */
    fun abandonBeforeDispatch(
        cancellation: PaymentCancellation,
        balance: ReturnBalance,
        attemptId: CancelAttemptId,
        cause: AbandonCause,
    ): CancelResolutionOutcome.Failed {
        require(balance.paymentId == cancellation.paymentId && balance.reservationOf(cancellation.id) != null) { "balance must hold this cancellation's reservation" }
        if (cancellation.status != OPEN) throw InvalidCancellationTransitionException(cancellation.id, cancellation.status.name, "abandonBeforeDispatch")
        val evidence = TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED
        cancellation.attempt(attemptId).abandon(cancellation.id, cause)
        cancellation.fail(evidence)
        balance.release(cancellation.id)
        return CancelResolutionOutcome.Failed(evidence)
    }

    /** 전송 뒤 결과 불명확(timeout·끊김·무결성 실패·dispatch lease 만료 등). 이미 불명확이면 효과 없음. 예약은 유지한다. */
    fun markOutcomeUnknown(cancellation: PaymentCancellation, attemptId: CancelAttemptId, cause: UnknownCause): CancelResolutionOutcome {
        val attempt = cancellation.attempt(attemptId)
        if (attempt.state == AttemptState.OUTCOME_UNKNOWN) return CancelResolutionOutcome.StillUnknown
        if (cancellation.status != OPEN) throw InvalidCancellationTransitionException(cancellation.id, cancellation.status.name, "markOutcomeUnknown")
        attempt.markOutcomeUnknown(cancellation.id, cause)
        cancellation.markUnknown()
        return CancelResolutionOutcome.BecameUnknown
    }

    private fun applyToPending(
        control: OrderPaymentControl,
        payment: Payment,
        cancellation: PaymentCancellation,
        balance: ReturnBalance,
        attempt: CancelAttempt,
        observation: CancelObservation,
        newTransactionId: PaymentTransactionId,
        at: Instant,
    ): CancelResolutionOutcome {
        // 이미 사건 처리된 취소는 이후 결과로 자동 확정·종료하지 않는다(D-CANCEL-006, D-AUTH-008과 같은 원칙).
        if (cancellation.incidentRecorded) return CancelResolutionOutcome.NoChange
        val definitive = observation.outcome == APPROVED || observation.outcome == DECLINED || observation.outcome == NO_EFFECT_CONFIRMED
        if (attempt.state == AttemptState.PREPARED) {
            // 보내지 않았다고 기록한 Attempt의 확정 결과는 계약 가정 위반이다.
            return if (definitive) conflict(control, payment, cancellation, attempt) else CancelResolutionOutcome.NoChange
        }
        return when (observation.outcome) {
            APPROVED -> {
                val terms = checkNotNull(observation.confirmed)
                if (!cancellation.matchesRequest(terms)) return conflict(control, payment, cancellation, attempt)
                attempt.resolve(cancellation.id)
                val transaction = CancelTransaction(
                    id = newTransactionId,
                    paymentId = cancellation.paymentId,
                    authorizationId = cancellation.authorizationId,
                    cancellationId = cancellation.id,
                    originalTransactionId = cancellation.originalTransactionId,
                    scope = cancellation.scope,
                    terms = terms,
                    sourceObservation = observation.key,
                    confirmedAt = at,
                )
                cancellation.complete(transaction)
                balance.consume(cancellation.id)
                CancelResolutionOutcome.Completed(transaction, checkNotNull(balance.completionOf(cancellation.id)), balance.authorized, balance.status)
            }
            DECLINED -> terminate(cancellation, balance, attempt, TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
            NO_EFFECT_CONFIRMED -> terminate(cancellation, balance, attempt, TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT)
            PENDING, InstitutionOutcome.UNKNOWN, NOT_FOUND ->
                if (cancellation.status == OPEN) {
                    if (attempt.state == AttemptState.DISPATCHING) attempt.markOutcomeUnknown(cancellation.id, UnknownCause.INSTITUTION_UNCONFIRMED)
                    cancellation.markUnknown()
                    CancelResolutionOutcome.BecameUnknown
                } else {
                    CancelResolutionOutcome.StillUnknown
                }
        }
    }

    private fun informational(outcome: InstitutionOutcome): Boolean = outcome == PENDING || outcome == InstitutionOutcome.UNKNOWN || outcome == NOT_FOUND

    private fun terminate(
        cancellation: PaymentCancellation,
        balance: ReturnBalance,
        attempt: CancelAttempt,
        evidence: TerminationEvidence,
    ): CancelResolutionOutcome.Failed {
        attempt.resolve(cancellation.id)
        cancellation.fail(evidence)
        balance.release(cancellation.id)
        return CancelResolutionOutcome.Failed(evidence)
    }

    /** 상충·늦은 성공: 진행 중이면 UNKNOWN으로(예약 유지), 상태·거래는 덮어쓰지 않고 이 Payment에 hold. */
    private fun conflict(control: OrderPaymentControl, payment: Payment, cancellation: PaymentCancellation, attempt: CancelAttempt): CancelResolutionOutcome {
        if (cancellation.isInFlight()) {
            if (attempt.state == AttemptState.DISPATCHING) attempt.markOutcomeUnknown(cancellation.id, UnknownCause.INSTITUTION_UNCONFIRMED)
            if (cancellation.status == OPEN) cancellation.markUnknown()
        }
        cancellation.recordIncident()
        control.reportCancellationResultConflict(payment)
        return CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
    }

    private fun requireSameOperation(control: OrderPaymentControl, payment: Payment, cancellation: PaymentCancellation, balance: ReturnBalance) {
        require(cancellation.paymentId == payment.id && cancellation.orderKey == payment.orderKey) { "cancellation must belong to the payment" }
        require(control.orderKey == payment.orderKey) { "control must be the payment's order" }
        require(balance.paymentId == payment.id && balance.reservationOf(cancellation.id) != null) { "balance must hold this cancellation's reservation" }
    }
}

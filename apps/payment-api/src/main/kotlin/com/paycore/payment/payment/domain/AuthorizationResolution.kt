package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationStatus.AUTHORIZED
import com.paycore.payment.payment.domain.AuthorizationStatus.FAILED
import com.paycore.payment.payment.domain.AuthorizationStatus.OPEN
import com.paycore.payment.payment.domain.AuthorizationStatus.UNKNOWN
import com.paycore.payment.payment.domain.InstitutionOutcome.APPROVED
import com.paycore.payment.payment.domain.InstitutionOutcome.DECLINED
import com.paycore.payment.payment.domain.InstitutionOutcome.NOT_FOUND
import com.paycore.payment.payment.domain.InstitutionOutcome.NO_EFFECT_CONFIRMED
import com.paycore.payment.payment.domain.InstitutionOutcome.PENDING
import java.time.Instant

/** 결과 반영의 업무 효과(application이 Outbox·멱등 결과를 고른다 — 명세 §11). */
sealed interface ResolutionOutcome {
    /** 승인 확정: 확정 금융 사실 1건(D-AUTH-003) → PaymentAuthorized. */
    data class Authorized(val transaction: AuthorizationTransaction, val incident: IncidentReason? = null) : ResolutionOutcome

    /** 무효과 확정(기관 거절·전송 전 실패·무효과) → 한도 해제. */
    data class Failed(val evidence: TerminationEvidence) : ResolutionOutcome

    /** 결과 불명확으로 바뀌었다(원 Attempt 조회로 회복). */
    data object BecameUnknown : ResolutionOutcome

    /** 계속 불명확(NOT_FOUND·PENDING·UNKNOWN은 종료 근거가 아니다). */
    data object StillUnknown : ResolutionOutcome

    /** 효과 없음(중복 수신·확정 뒤 같은 결과·이미 사건 보류 중) — 관찰은 기록됐을 수 있다. */
    data object NoChange : ResolutionOutcome

    /** 사건 보류(D-AUTH-002·D-AUTH-008) → AuthorizationIncidentDetected. 자동 취소 없음. */
    data class Incident(val reason: IncidentReason) : ResolutionOutcome
}

/**
 * 기관 결과를 Payment·Authorization·주문 control·일 한도에 한 연산으로 반영한다(AUTH-CONTRACT-001 §5.6 판정표, TX2·회복 경로 공통).
 * 먼저 온 결과나 마지막 결과를 무조건 정답으로 하지 않는다(허용 §5.2). 확정 금융 사실은 덮어쓰지 않는다.
 *
 * - timeout·NOT_FOUND·lease 만료는 종료 근거가 아니다 → UNKNOWN 유지(시도 §2·§4, INV-11).
 * - 종결 전 조건 상충 승인 → UNKNOWN 유지 + hold(CONFLICTING_INSTITUTION_RESULT), 거래·AUTHORIZED 없음, 한도 점유 유지(D-AUTH-008).
 * - 확정 거절·무효과 뒤 승인 → 상태 유지 + hold(SUCCESS_AFTER_TERMINATION), 거래 없음(D-AUTH-002·D-AUTH-003).
 * - 승인 확정 뒤 "같은 승인"(기관 승인 조건 전체 — 금액·Plan·cardType·**ProviderTransactionId·승인번호·기관 거래일** — 가 확정 거래와
 *   같음, review M-1)만 효과 없음. 식별자가 하나라도 다르면 별개의 기관 승인일 수 있으므로 상태 유지 + hold(CONFLICTING_INSTITUTION_RESULT),
 *   거래 없음.
 * - 해제된 일 한도에 늦은 승인 → 같은 날짜에 다시 기록(FORCED, 초과여도 거부하지 않고 초과 표시, 새 승인 한도 판정 합에 포함 — D-AUTH-010).
 * - 같은 관찰 키·다른 내용 → 상충 증거로 기록 + hold(CONFLICTING_INSTITUTION_RESULT)(review m-1). 같은 키·같은 내용만 효과 없음.
 * - 이전 generation hold 중이어도 현재 generation의 일치 승인은 거래·AUTHORIZED로 기록하고 hold는 유지한다(D-AUTH-011).
 */
object AuthorizationResolution {

    /**
     * @param limitPolicy 지금 적용되는 한도 정책(해석 결과 Resolved). 늦은 승인의 강제 재점유(D-AUTH-010)에서 초과 표시에만 쓰며, 알 수 없으면
     *   null(초과 여부 UNDETERMINED로 기록). 다른 경로의 판정에는 쓰지 않는다.
     */
    fun applyObservation(
        control: OrderPaymentControl,
        payment: Payment,
        authorization: PaymentAuthorization,
        usage: DailyLimitUsage,
        observation: InstitutionObservation,
        newTransactionId: PaymentTransactionId,
        limitPolicy: TransactionLimitPolicy?,
        at: Instant,
    ): ResolutionOutcome {
        requireSameOperation(control, payment, authorization, usage)
        when (authorization.recordObservation(observation)) {
            ObservationRecord.DUPLICATE -> return ResolutionOutcome.NoChange
            ObservationRecord.KEY_CONFLICT -> {
                val attempt = authorization.attempt(observation.attemptId)
                return if (authorization.status == OPEN || authorization.status == UNKNOWN) {
                    conflict(control, payment, authorization, attempt)
                } else {
                    val held = control.reportConflictingResult(payment)
                    // review round2 m-7: FAILED 승인에 같은 키·다른 내용의 승인이 오면 기관 승인이 있을 수 있다 → 한도 재점유(D-AUTH-010).
                    if (held && authorization.status == FAILED && observation.outcome == APPROVED) {
                        usage.reoccupyAfterLateApproval(authorization.id, limitPolicy)
                    }
                    incidentIfHeld(held, IncidentReason.CONFLICTING_INSTITUTION_RESULT)
                }
            }
            ObservationRecord.NEW -> {}
        }
        val attempt = authorization.attempt(observation.attemptId)
        return when (authorization.status) {
            OPEN, UNKNOWN -> applyToPending(control, payment, authorization, usage, attempt, observation, newTransactionId, at)
            AUTHORIZED -> applyToAuthorized(control, payment, authorization, observation)
            FAILED ->
                if (observation.outcome == APPROVED) {
                    // D-AUTH-012: NO_EFFECT로 해제한 뒤 새 승인 증거면 새 hold(occurrence + 1) + FORCED 재점유.
                    val held = control.reportSuccessAfterTermination(payment)
                    if (held) usage.reoccupyAfterLateApproval(authorization.id, limitPolicy)
                    incidentIfHeld(held, IncidentReason.SUCCESS_AFTER_TERMINATION)
                } else {
                    ResolutionOutcome.NoChange
                }
        }
    }

    /** 전송 전 포기(Attempt ABANDONED_BEFORE_DISPATCH)를 Payment FAILED(PRE_TRANSMISSION_FAILURE_CONFIRMED)와 한도 해제에 반영한다. */
    fun abandonBeforeDispatch(
        payment: Payment,
        authorization: PaymentAuthorization,
        usage: DailyLimitUsage,
        attemptId: AttemptId,
        cause: AbandonCause,
    ): ResolutionOutcome.Failed {
        requireSameOperation(null, payment, authorization, usage)
        if (authorization.status != OPEN) throw InvalidAuthorizationTransitionException(authorization.id, authorization.status.name, "abandon")
        if (payment.status != PaymentStatus.AUTHORIZING) throw InvalidPaymentTransitionException(payment.id, payment.status, "abandonBeforeDispatch")
        val evidence = TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED
        authorization.attempt(attemptId).abandon(authorization.id, cause)
        authorization.fail(evidence)
        payment.fail(evidence)
        usage.release(authorization.id)
        return ResolutionOutcome.Failed(evidence)
    }

    /** 전송 뒤 결과 불명확(timeout·끊김·무결성 실패·dispatch lease 만료 등). 이미 불명확이면 효과 없음. */
    fun markOutcomeUnknown(
        payment: Payment,
        authorization: PaymentAuthorization,
        attemptId: AttemptId,
        cause: UnknownCause,
    ): ResolutionOutcome {
        require(authorization.paymentId == payment.id) { "authorization must belong to the payment" }
        val attempt = authorization.attempt(attemptId)
        if (attempt.state == AttemptState.OUTCOME_UNKNOWN) return ResolutionOutcome.StillUnknown
        if (authorization.status != OPEN) throw InvalidAuthorizationTransitionException(authorization.id, authorization.status.name, "markOutcomeUnknown")
        attempt.markOutcomeUnknown(authorization.id, cause)
        becomeUnknown(payment, authorization)
        return ResolutionOutcome.BecameUnknown
    }

    private fun applyToPending(
        control: OrderPaymentControl,
        payment: Payment,
        authorization: PaymentAuthorization,
        usage: DailyLimitUsage,
        attempt: AuthorizationAttempt,
        observation: InstitutionObservation,
        newTransactionId: PaymentTransactionId,
        at: Instant,
    ): ResolutionOutcome {
        // 이미 상충으로 사건 보류된 승인은 이후 결과로 자동 확정하지 않는다(해소는 수동·2인·재대사 MATCH, D-AUTH-008).
        if (control.hasActiveHold(payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT)) return ResolutionOutcome.NoChange
        val definitive = observation.outcome == APPROVED || observation.outcome == DECLINED || observation.outcome == NO_EFFECT_CONFIRMED
        if (attempt.state == AttemptState.PREPARED) {
            // 보내지 않았다고 기록한 Attempt의 확정 결과는 계약 가정 위반이다.
            return if (definitive) conflict(control, payment, authorization, attempt) else ResolutionOutcome.NoChange
        }
        return when (observation.outcome) {
            APPROVED -> {
                val terms = checkNotNull(observation.approved)
                if (!authorization.matchesRequest(terms)) return conflict(control, payment, authorization, attempt)
                // control 검사(현재 슬롯·상태)가 실패하면 아무것도 바뀌지 않도록 먼저 한다.
                val doubleApprovalHold = control.completeAuthorization(payment)
                attempt.resolve(authorization.id)
                val transaction = AuthorizationTransaction(
                    id = newTransactionId,
                    authorizationId = authorization.id,
                    paymentId = payment.id,
                    terms = terms,
                    sourceObservation = observation.key,
                    confirmedAt = at,
                )
                authorization.authorize(transaction)
                usage.consume(authorization.id)
                ResolutionOutcome.Authorized(transaction, if (doubleApprovalHold) IncidentReason.CONFLICTING_INSTITUTION_RESULT else null)
            }
            DECLINED -> terminate(payment, authorization, usage, attempt, TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
            NO_EFFECT_CONFIRMED -> terminate(payment, authorization, usage, attempt, TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT)
            PENDING, InstitutionOutcome.UNKNOWN, NOT_FOUND ->
                if (authorization.status == OPEN) {
                    if (attempt.state == AttemptState.DISPATCHING) attempt.markOutcomeUnknown(authorization.id, UnknownCause.INSTITUTION_UNCONFIRMED)
                    becomeUnknown(payment, authorization)
                    ResolutionOutcome.BecameUnknown
                } else {
                    ResolutionOutcome.StillUnknown
                }
        }
    }

    private fun applyToAuthorized(
        control: OrderPaymentControl,
        payment: Payment,
        authorization: PaymentAuthorization,
        observation: InstitutionObservation,
    ): ResolutionOutcome {
        val confirmed = checkNotNull(authorization.transaction).terms
        // review M-1: 조건이 같아도 ProviderTransactionId·승인번호·기관 거래일이 다르면 별개의 승인일 수 있다 → 같은 승인이 아니다.
        val sameApproval = observation.outcome == APPROVED && observation.approved == confirmed
        val informational = observation.outcome == PENDING || observation.outcome == InstitutionOutcome.UNKNOWN || observation.outcome == NOT_FOUND
        if (sameApproval || informational) return ResolutionOutcome.NoChange
        return incidentIfHeld(control.reportConflictingResult(payment), IncidentReason.CONFLICTING_INSTITUTION_RESULT)
    }

    private fun terminate(
        payment: Payment,
        authorization: PaymentAuthorization,
        usage: DailyLimitUsage,
        attempt: AuthorizationAttempt,
        evidence: TerminationEvidence,
    ): ResolutionOutcome.Failed {
        attempt.resolve(authorization.id)
        authorization.fail(evidence)
        payment.fail(evidence)
        usage.release(authorization.id)
        return ResolutionOutcome.Failed(evidence)
    }

    private fun conflict(
        control: OrderPaymentControl,
        payment: Payment,
        authorization: PaymentAuthorization,
        attempt: AuthorizationAttempt,
    ): ResolutionOutcome {
        if (attempt.state == AttemptState.DISPATCHING) attempt.markOutcomeUnknown(authorization.id, UnknownCause.INSTITUTION_UNCONFIRMED)
        if (authorization.status == OPEN) becomeUnknown(payment, authorization)
        return incidentIfHeld(control.reportConflictingResult(payment), IncidentReason.CONFLICTING_INSTITUTION_RESULT)
    }

    /** Incident는 활성 hold가 있을 때만 돌려준다 — 활성 hold·FORCED·Incident 반환이 항상 맞는다(review round2 m-6). */
    private fun incidentIfHeld(held: Boolean, reason: IncidentReason): ResolutionOutcome =
        if (held) ResolutionOutcome.Incident(reason) else ResolutionOutcome.NoChange

    private fun becomeUnknown(payment: Payment, authorization: PaymentAuthorization) {
        authorization.markUnknown()
        if (payment.status == PaymentStatus.AUTHORIZING) payment.markUnknown()
    }

    private fun requireSameOperation(control: OrderPaymentControl?, payment: Payment, authorization: PaymentAuthorization, usage: DailyLimitUsage) {
        require(authorization.paymentId == payment.id) { "authorization must belong to the payment" }
        require(control == null || control.orderKey == payment.orderKey) { "control must be the payment's order" }
        require(usage.key == authorization.limitKey) { "limit usage must be the authorization's reservation scope" }
        val expectedPaymentStatus = when (authorization.status) {
            OPEN -> PaymentStatus.AUTHORIZING
            UNKNOWN -> PaymentStatus.UNKNOWN
            AUTHORIZED -> PaymentStatus.AUTHORIZED
            FAILED -> PaymentStatus.FAILED
        }
        require(payment.status == expectedPaymentStatus) { "payment ${payment.status} is inconsistent with authorization ${authorization.status}" }
        if (control != null && (authorization.status == OPEN || authorization.status == UNKNOWN)) {
            // 진행 중 승인의 Payment는 주문의 현재 슬롯이다(어떤 관찰도 기록하기 전에 확인 — 실패 시 아무것도 바뀌지 않는다).
            if (control.currentPaymentId != payment.id || control.generation != payment.slotGeneration) {
                throw StaleSlotException(control.orderKey, control.currentPaymentId, control.generation, payment.id, payment.slotGeneration)
            }
        }
    }
}

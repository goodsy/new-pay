package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AttemptState.ABANDONED_BEFORE_DISPATCH
import com.paycore.payment.payment.domain.AttemptState.DISPATCHING
import com.paycore.payment.payment.domain.AttemptState.OUTCOME_UNKNOWN
import com.paycore.payment.payment.domain.AttemptState.PREPARED
import com.paycore.payment.payment.domain.AttemptState.RESOLVED
import java.time.Instant

/**
 * 기관 취소 실행 시도(PaymentCancellation 안의 Entity, CANCEL-CONTRACT-001 §4.1). dispatch 상태는 승인 Attempt와 같은 의미([AttemptState])다:
 * PREPARED → DISPATCHING 커밋 뒤에만 기관에 연결하고, 그 뒤 실패는 "취소가 실행됐을 수 있음"이다. 전송 전 포기([AbandonCause])만 예약 해제
 * 근거(PRE_TRANSMISSION_FAILURE_CONFIRMED)의 출처다. 전이는 [PaymentCancellation]·취소 결과 반영 domain 서비스를 거친다.
 */
class CancelAttempt internal constructor(
    val id: CancelAttemptId,
    val providerRequest: ProviderRequestRef,
    val preparedAt: Instant,
    state: AttemptState,
    abandonCause: AbandonCause?,
    unknownCause: UnknownCause?,
) {
    var state: AttemptState = state
        private set

    var abandonCause: AbandonCause? = abandonCause
        private set

    var unknownCause: UnknownCause? = unknownCause
        private set

    /** 전송했거나 전송 여부가 불명확하다. */
    fun mayHaveBeenSent(): Boolean = state == DISPATCHING || state == OUTCOME_UNKNOWN || state == RESOLVED

    internal fun beginDispatch(owner: CancellationId) {
        if (state != PREPARED) throw InvalidCancellationTransitionException(owner, "attempt $state", "beginDispatch")
        state = DISPATCHING
    }

    internal fun abandon(owner: CancellationId, cause: AbandonCause) {
        val allowed = when (cause) {
            AbandonCause.GATE_REFUSED, AbandonCause.FENCED_BY_RECOVERY -> state == PREPARED
            AbandonCause.CONNECT_FAILED -> state == DISPATCHING
        }
        if (!allowed) throw InvalidCancellationTransitionException(owner, "attempt $state", "abandon($cause)")
        state = ABANDONED_BEFORE_DISPATCH
        abandonCause = cause
    }

    internal fun markOutcomeUnknown(owner: CancellationId, cause: UnknownCause) {
        if (state != DISPATCHING) throw InvalidCancellationTransitionException(owner, "attempt $state", "markOutcomeUnknown")
        state = OUTCOME_UNKNOWN
        unknownCause = cause
    }

    internal fun resolve(owner: CancellationId) {
        if (state != DISPATCHING && state != OUTCOME_UNKNOWN) throw InvalidCancellationTransitionException(owner, "attempt $state", "resolve")
        state = RESOLVED
    }

    override fun toString(): String = "CancelAttempt(id=${id.value}, state=$state)"
}

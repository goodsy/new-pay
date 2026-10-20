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
 *
 * [routeProfileVersion](D-CANCEL1-004, CANCEL-001 F-2): 전송 게이트 통과 때 고정한 Route 프로파일(Capability) 버전. PREPARED에는 없다. 회복은 이 값이 현재 등록부
 * 버전과 같을 때만 기관 무효과 종결을 요청하고, 다르거나 없으면 조회·이관만 한다(fail-closed).
 */
class CancelAttempt internal constructor(
    val id: CancelAttemptId,
    val providerRequest: ProviderRequestRef,
    val preparedAt: Instant,
    state: AttemptState,
    abandonCause: AbandonCause?,
    unknownCause: UnknownCause?,
    routeProfileVersion: String? = null,
) {
    init {
        require(state != PREPARED || routeProfileVersion == null) { "a PREPARED cancel attempt has no route profile version yet" }
        require(routeProfileVersion == null || isValidRouteProfileVersion(routeProfileVersion)) { "route profile version must be 1..64 printable ASCII" }
    }

    var state: AttemptState = state
        private set

    var routeProfileVersion: String? = routeProfileVersion
        private set

    var abandonCause: AbandonCause? = abandonCause
        private set

    var unknownCause: UnknownCause? = unknownCause
        private set

    /** 전송했거나 전송 여부가 불명확하다. */
    fun mayHaveBeenSent(): Boolean = state == DISPATCHING || state == OUTCOME_UNKNOWN || state == RESOLVED

    internal fun beginDispatch(owner: CancellationId, routeProfileVersion: String) {
        require(isValidRouteProfileVersion(routeProfileVersion)) { "route profile version must be 1..64 printable ASCII" }
        if (state != PREPARED) throw InvalidCancellationTransitionException(owner, "attempt $state", "beginDispatch")
        state = DISPATCHING
        this.routeProfileVersion = routeProfileVersion
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

    private companion object {
        const val MAX_ROUTE_PROFILE_VERSION = 64

        fun isValidRouteProfileVersion(v: String): Boolean = v.isNotEmpty() && v.length <= MAX_ROUTE_PROFILE_VERSION && v.all { it in '!'..'~' }
    }
}

package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AttemptState.ABANDONED_BEFORE_DISPATCH
import com.paycore.payment.payment.domain.AttemptState.DISPATCHING
import com.paycore.payment.payment.domain.AttemptState.OUTCOME_UNKNOWN
import com.paycore.payment.payment.domain.AttemptState.PREPARED
import com.paycore.payment.payment.domain.AttemptState.RESOLVED
import java.time.Instant

/**
 * 기관 요청 ID와 namespace(허용 §5.2: 기관 + 환경 + 기관 계약계정 + operation + 요청 ID). 내부 업무 ID(AttemptId)와 따로 보존한다(시도 §4).
 */
data class ProviderRequestRef(
    val institution: String,
    val environment: Environment,
    val institutionAccount: String,
    val operation: String,
    val requestId: String,
) {
    init {
        require(institution.isNotEmpty() && institutionAccount.isNotEmpty() && operation.isNotEmpty() && requestId.isNotEmpty()) {
            "provider request namespace parts must not be empty"
        }
    }

    /** 기관 계약계정([institutionAccount])은 내부 식별자 등급(D-MS2-005) — 문자열 표현·로그·예외에 원문을 싣지 않는다(AUTH-001 F-3). */
    override fun toString(): String = "ProviderRequestRef(institution=$institution, environment=$environment, institutionAccount=***, operation=$operation, requestId=$requestId)"
}

/**
 * Attempt dispatch 상태(허용 §5.3-2, AUTH-CONTRACT-001 §5.2). PREPARED에서 DISPATCHING으로 바뀌는 커밋 뒤에만 소켓을 연다 — 그 뒤
 * 실패는 "전송했을 수 있음"이다. 영속 경쟁(전송 vs 회복의 포기)은 같은 행 version 조건부 갱신이 막는다(AUTH-001).
 */
enum class AttemptState { PREPARED, DISPATCHING, ABANDONED_BEFORE_DISPATCH, OUTCOME_UNKNOWN, RESOLVED }

/** 전송 전 포기 원인. 이것만 PRE_TRANSMISSION_FAILURE_CONFIRMED의 출처다(명세 §5.5). */
enum class AbandonCause {
    /** 전송 게이트 거절(차단·lease 만료·Health) — PREPARED에서. */
    GATE_REFUSED,

    /** 회복 Worker가 기한 지난 PREPARED를 fencing으로 포기 — PREPARED에서. */
    FENCED_BY_RECOVERY,

    /** connect 단계 실패로 byte를 보내지 않았고 연결 예산을 소진 — DISPATCHING에서(전송자만 판단). */
    CONNECT_FAILED,
}

/** 결과 불명확 원인(진단용). 어느 것도 무효과 근거가 아니다. */
enum class UnknownCause { TIMEOUT, CONNECTION_LOST, FRAMING, CORRELATION, INTEGRITY, DISPATCH_LEASE_EXPIRED, COMMIT_FAILURE_RECOVERY, INSTITUTION_UNCONFIRMED }

/**
 * 기관 승인 실행 시도(PaymentAuthorization 안의 Entity). 전이는 [PaymentAuthorization]·[AuthorizationResolution]을 거친다.
 * [routeProfileVersion](D-AUTH1-007): 전송 게이트 통과 때 고정한 Route 프로파일(Capability) 버전 — PREPARED에는 없고, 전송한 Attempt에 없으면(V14 이전 행) 회복은 현재 버전과
 * 다르다고 본다(무효과 종결 요청 없음 — fail-closed).
 */
class AuthorizationAttempt internal constructor(
    val id: AttemptId,
    val providerRequest: ProviderRequestRef,
    val preparedAt: Instant,
    state: AttemptState,
    abandonCause: AbandonCause?,
    unknownCause: UnknownCause?,
    routeProfileVersion: String? = null,
) {
    init {
        require(state != PREPARED || routeProfileVersion == null) { "a PREPARED attempt has no route profile version yet" }
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

    internal fun beginDispatch(owner: AuthorizationId, routeProfileVersion: String) {
        require(isValidRouteProfileVersion(routeProfileVersion)) { "route profile version must be 1..64 printable ASCII" }
        if (state != PREPARED) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "beginDispatch")
        state = DISPATCHING
        this.routeProfileVersion = routeProfileVersion
    }

    internal fun abandon(owner: AuthorizationId, cause: AbandonCause) {
        val allowed = when (cause) {
            AbandonCause.GATE_REFUSED, AbandonCause.FENCED_BY_RECOVERY -> state == PREPARED
            AbandonCause.CONNECT_FAILED -> state == DISPATCHING
        }
        if (!allowed) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "abandon($cause)")
        state = ABANDONED_BEFORE_DISPATCH
        abandonCause = cause
    }

    internal fun markOutcomeUnknown(owner: AuthorizationId, cause: UnknownCause) {
        if (state != DISPATCHING) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "markOutcomeUnknown")
        state = OUTCOME_UNKNOWN
        unknownCause = cause
    }

    internal fun resolve(owner: AuthorizationId) {
        if (state != DISPATCHING && state != OUTCOME_UNKNOWN) throw InvalidAuthorizationTransitionException(owner, "attempt $state", "resolve")
        state = RESOLVED
    }

    override fun toString(): String = "AuthorizationAttempt(id=${id.value}, state=$state)"

    private companion object {
        const val MAX_ROUTE_PROFILE_VERSION = 64

        fun isValidRouteProfileVersion(v: String): Boolean = v.isNotEmpty() && v.length <= MAX_ROUTE_PROFILE_VERSION && v.all { it in '!'..'~' }
    }
}

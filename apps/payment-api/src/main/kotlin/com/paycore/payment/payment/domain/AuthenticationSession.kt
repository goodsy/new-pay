package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthenticationStatus.CREATED
import com.paycore.payment.payment.domain.AuthenticationStatus.EXPIRED
import com.paycore.payment.payment.domain.AuthenticationStatus.FAILED
import com.paycore.payment.payment.domain.AuthenticationStatus.IN_PROGRESS
import com.paycore.payment.payment.domain.AuthenticationStatus.VERIFIED
import java.time.Instant

/** 고객 카드 인증 lifecycle(D-AUTH-001, 사용자 확정 2026-10-15). 인증 성공(VERIFIED) ≠ 승인 성공. */
enum class AuthenticationStatus { CREATED, IN_PROGRESS, VERIFIED, FAILED, EXPIRED }

/**
 * 인증 세션이 묶인 결제 조건(범위 §3-2·§3-4): Payment·주문·금액·통화·선택 PaymentPlan·카드사. 환경·MID는 [orderKey]에 있다.
 * [cardCompany]는 고객이 결제창에서 고른 카드사 코드(기관 프로파일 allowlist 판정은 승인 시작 허용 판정 Port의 책임).
 */
data class AuthenticationBinding(
    val paymentId: PaymentId,
    val orderKey: OrderKey,
    val money: Money,
    val plan: PaymentPlan,
    val cardCompany: String,
) {
    init {
        require(cardCompany.isNotEmpty()) { "cardCompany must not be empty" }
    }
}

/**
 * 서버가 검증한 인증 증거(범위 §3-4·§4): 기관 증거의 불투명 참조, 증거가 대상으로 한 바인딩, 기관이 알려 준 cardType과
 * issuer/acquirer/authenticationProvider 식별. 카드번호·CVC·인증 비밀은 없다(가상 카드 참조만, 허용 §5.5).
 */
data class VerifiedAuthentication(
    val evidenceRef: String,
    val binding: AuthenticationBinding,
    val cardType: CardType,
    val issuerCode: String,
    val acquirerCode: String,
    val providerCode: String,
) {
    init {
        require(evidenceRef.isNotEmpty()) { "evidenceRef must not be empty" }
        require(issuerCode.isNotEmpty() && acquirerCode.isNotEmpty() && providerCode.isNotEmpty()) {
            "issuer, acquirer and authentication provider codes must not be empty"
        }
    }
}

/** 인증 실패 사유(내부 분류, 외부에는 노출하지 않는다). */
enum class AuthenticationFailure { AUTHENTICATION_FAILED, USER_CANCELLED, BINDING_MISMATCH, STATE_MISMATCH }

/** 세션 만료 원인. */
enum class AuthenticationExpiryCause {
    /** 세션 유효기간 경과(수치는 버전 있는 설정, D-AUTH-009). */
    TTL_ELAPSED,

    /** 결제창(Payment REQUESTED)이 만료·종료돼 세션을 폐기한다. */
    PAYMENT_CLOSED,
}

/** [AuthenticationSession.verify] 결과. */
enum class VerificationOutcome {
    VERIFIED,

    /** 같은 증거의 중복 callback(멱등). */
    DUPLICATE,

    /** 증거의 바인딩이 세션과 다르다 → 세션 FAILED(BINDING_MISMATCH). */
    REJECTED_BINDING_MISMATCH,

    /** 유효기간이 지난 뒤 도착 → 세션 EXPIRED. */
    EXPIRED,

    /** 이미 종료(FAILED·EXPIRED)된 세션의 늦은 결과. 기록만 하고 승인 권한을 만들지 않는다. */
    IGNORED_TERMINAL,
}

/** 승인 시작에서 인증 근거를 쓸 수 없는 이유(외부 노출은 고정 분류 AUTHENTICATION_NOT_VERIFIED만 — 명세 §10.1). */
enum class BindingRejection { NOT_VERIFIED, EXPIRED, ALREADY_BOUND, OTHER_PAYMENT, AMOUNT_MISMATCH }

/**
 * 고객 카드 인증 세션(Aggregate Root, PAYMENT DB — AUTH-CONTRACT-001 §3·§5.3).
 *
 * 불변식:
 * - 같은 Payment에 세션 이력은 여러 개일 수 있지만 비종료(CREATED·IN_PROGRESS) 세션은 하나, 귀속 세션도 최대 하나다([create]).
 * - 인증 근거는 **하나의 논리 승인 operation에만** 귀속한다: [bindTo]는 VERIFIED·미귀속·유효기간 내에서 한 번만, 되돌리지 않는다
 *   (D-AUTH-001). 귀속 뒤에는 FAILED·EXPIRED로 바뀌지 않는다 — TTL 만료가 이미 시작한 승인을 바꾸지 않는다(범위 §3).
 * - VERIFIED 세션에 다른 증거가 오면 덮어쓰지 않는다([AuthenticationEvidenceConflictException]).
 * - 승인 시작이 거절되면 세션은 귀속되지 않는다(D-AUTH-005).
 */
class AuthenticationSession private constructor(
    val id: AuthenticationSessionId,
    val binding: AuthenticationBinding,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    var status: AuthenticationStatus = CREATED
        private set

    var verified: VerifiedAuthentication? = null
        private set

    var failure: AuthenticationFailure? = null
        private set

    var boundAuthorizationId: AuthorizationId? = null
        private set

    /** 비종료(CREATED·IN_PROGRESS). */
    fun isOpen(): Boolean = status == CREATED || status == IN_PROGRESS

    /** 폐기됨(FAILED·EXPIRED). Payment 만료 근거 NOT_TRANSMITTED_AND_SESSION_DISCARDED의 세션 조건이다. */
    fun isDiscarded(): Boolean = status == FAILED || status == EXPIRED

    fun isBound(): Boolean = boundAuthorizationId != null

    /** 결제창이 인증기관으로 연결했다(CREATED → IN_PROGRESS). */
    fun start() {
        if (status != CREATED) throw InvalidAuthenticationTransitionException(id, status, "start")
        status = IN_PROGRESS
    }

    /**
     * 서버 검증 결과를 반영한다. IN_PROGRESS에서만 VERIFIED가 된다(바인딩 일치·유효기간 내). 같은 증거 중복은 멱등, 다른 증거는 거부,
     * 이미 종료된 세션의 늦은 결과는 무시한다.
     */
    fun verify(evidence: VerifiedAuthentication, now: Instant): VerificationOutcome {
        when (status) {
            VERIFIED -> {
                if (evidence == verified) return VerificationOutcome.DUPLICATE
                throw AuthenticationEvidenceConflictException(id)
            }
            FAILED, EXPIRED -> return VerificationOutcome.IGNORED_TERMINAL
            CREATED -> throw InvalidAuthenticationTransitionException(id, status, "verify")
            IN_PROGRESS -> {}
        }
        if (!now.isBefore(expiresAt)) {
            status = EXPIRED
            return VerificationOutcome.EXPIRED
        }
        if (evidence.binding != binding) {
            status = FAILED
            failure = AuthenticationFailure.BINDING_MISMATCH
            return VerificationOutcome.REJECTED_BINDING_MISMATCH
        }
        verified = evidence
        status = VERIFIED
        return VerificationOutcome.VERIFIED
    }

    /** 인증 실패·사용자 취소 등(CREATED·IN_PROGRESS → FAILED). 같은 Payment에서 새 세션을 시작할 수 있다. */
    fun fail(reason: AuthenticationFailure) {
        if (!isOpen()) throw InvalidAuthenticationTransitionException(id, status, "fail($reason)")
        status = FAILED
        failure = reason
    }

    /**
     * 만료한다(CREATED·IN_PROGRESS·VERIFIED(미귀속) → EXPIRED). [AuthenticationExpiryCause.TTL_ELAPSED]는 유효기간이 지나야 한다.
     * 귀속된 세션은 만료하지 않는다.
     */
    fun expire(cause: AuthenticationExpiryCause, now: Instant) {
        if (isDiscarded() || isBound()) throw InvalidAuthenticationTransitionException(id, status, "expire($cause)")
        if (cause == AuthenticationExpiryCause.TTL_ELAPSED && now.isBefore(expiresAt)) {
            throw InvalidAuthenticationTransitionException(id, status, "expire($cause) before expiresAt")
        }
        status = EXPIRED
    }

    /** 이 세션의 인증 근거로 [paymentId]·[money]의 승인을 시작할 수 없는 이유(없으면 null). 상태를 바꾸지 않는다. */
    fun bindingRejection(paymentId: PaymentId, money: Money, now: Instant): BindingRejection? = when {
        binding.paymentId != paymentId -> BindingRejection.OTHER_PAYMENT
        isBound() -> BindingRejection.ALREADY_BOUND
        status != VERIFIED -> BindingRejection.NOT_VERIFIED
        !now.isBefore(expiresAt) -> BindingRejection.EXPIRED
        binding.money != money -> BindingRejection.AMOUNT_MISMATCH
        else -> null
    }

    /**
     * 인증 근거를 승인 operation [authorizationId]에 귀속한다(한 번만). main 코드에서는 [AuthorizationStart]만 부른다(ArchUnit
     * controlStartAuthorizationOnlyThroughAuthorizationStart).
     */
    fun bindTo(authorizationId: AuthorizationId, paymentId: PaymentId, money: Money, now: Instant) {
        val rejection = bindingRejection(paymentId, money, now)
        if (rejection != null) throw InvalidAuthenticationTransitionException(id, status, "bindTo($rejection)")
        boundAuthorizationId = authorizationId
    }

    override fun toString(): String = "AuthenticationSession(id=${id.value}, status=$status, bound=${boundAuthorizationId?.value})"

    companion object {
        /**
         * REQUESTED [payment]에 새 인증 세션을 연다. [sessionsOfPayment]는 같은 Payment의 기존 세션 전부다(비종료·귀속 세션이 있으면 거부).
         */
        fun create(
            id: AuthenticationSessionId,
            payment: Payment,
            binding: AuthenticationBinding,
            expiresAt: Instant,
            now: Instant,
            sessionsOfPayment: List<AuthenticationSession>,
        ): AuthenticationSession {
            require(payment.status == PaymentStatus.REQUESTED) { "authentication starts only for a REQUESTED payment: ${payment.status}" }
            require(binding.paymentId == payment.id && binding.orderKey == payment.orderKey) { "binding must target this payment" }
            require(expiresAt.isAfter(now)) { "expiresAt must be after now" }
            require(sessionsOfPayment.all { it.binding.paymentId == payment.id }) { "sessions must belong to this payment" }
            require(sessionsOfPayment.none { it.isOpen() }) { "this payment already has an open authentication session" }
            require(sessionsOfPayment.none { it.isBound() }) { "this payment already has a bound authentication session" }
            require(sessionsOfPayment.none { it.id == id }) { "authentication session id is already used" }
            return AuthenticationSession(id, binding, expiresAt, now)
        }

        /** 영속 복원(persistence Mapper 전용, AUTH-001). 존재할 수 없는 상태 조합은 거부한다. */
        fun reconstitute(
            id: AuthenticationSessionId,
            binding: AuthenticationBinding,
            expiresAt: Instant,
            createdAt: Instant,
            status: AuthenticationStatus,
            verified: VerifiedAuthentication?,
            failure: AuthenticationFailure?,
            boundAuthorizationId: AuthorizationId?,
        ): AuthenticationSession {
            require((status == VERIFIED) == (verified != null)) { "verified evidence exists exactly for VERIFIED sessions" }
            require(verified == null || verified.binding == binding) { "verified evidence must match the binding" }
            require(boundAuthorizationId == null || status == VERIFIED) { "only a VERIFIED session can be bound" }
            require(failure == null || status == FAILED) { "a failure reason exists only for FAILED sessions" }
            return AuthenticationSession(id, binding, expiresAt, createdAt).also {
                it.status = status
                it.verified = verified
                it.failure = failure
                it.boundAuthorizationId = boundAuthorizationId
            }
        }
    }
}

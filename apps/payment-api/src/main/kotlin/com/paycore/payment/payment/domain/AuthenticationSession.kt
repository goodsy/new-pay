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
 * [cardCompany]는 고객이 결제창에서 고른 카드사(발급사) 코드다. 기관 결과의 issuerCode와 대조한다(AUTHN-001 §6.2·§7.3 R7).
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
 * issuer/acquirer/authenticationProvider 식별, 기관이 정한 근거 유효기간([providerValidUntil], AUTHN-001 §7.1). 카드번호·CVC·인증 비밀은 없다.
 */
data class VerifiedAuthentication(
    val evidenceRef: String,
    val binding: AuthenticationBinding,
    val cardType: CardType,
    val issuerCode: String,
    val acquirerCode: String,
    val providerCode: String,
    val providerValidUntil: Instant,
) {
    init {
        require(evidenceRef.isNotEmpty()) { "evidenceRef must not be empty" }
        require(issuerCode.isNotEmpty() && acquirerCode.isNotEmpty() && providerCode.isNotEmpty()) {
            "issuer, acquirer and authentication provider codes must not be empty"
        }
    }
}

/** 인증 실패 사유(내부 분류, 외부에는 노출하지 않는다). */
enum class AuthenticationFailure {
    AUTHENTICATION_FAILED,
    USER_CANCELLED,
    BINDING_MISMATCH,
    STATE_MISMATCH,

    /** 기관이 결과(근거) 유효기간 경과를 확정했다(AUTHN-001 §7.3 R5). 세션 TTL 만료(EXPIRED)와 다르다. */
    PROVIDER_EXPIRED,
}

/** 세션 만료 원인(감사용으로 저장 — AUTHN-001 §7.1). */
enum class AuthenticationExpiryCause {
    /** 세션 유효기간 경과(수치는 버전 있는 설정, D-AUTH-009). */
    TTL_ELAPSED,

    /** 결제창(Payment REQUESTED)이 만료·종료돼 세션을 폐기한다. */
    PAYMENT_CLOSED,

    /** 같은 Payment의 새 인증 시작이 이 세션을 대체했다(AN-07). */
    SUPERSEDED,
}

/** [AuthenticationSession.verify] 결과. */
enum class VerificationOutcome {
    VERIFIED,

    /** 같은 증거·같은 결과 참조의 중복(멱등). */
    DUPLICATE,

    /** 증거의 바인딩이 세션과 다르다 → 세션 FAILED(BINDING_MISMATCH). */
    REJECTED_BINDING_MISMATCH,

    /** 증거의 기관 유효기간이 이미 지났다 → 세션 FAILED(PROVIDER_EXPIRED). */
    REJECTED_PROVIDER_EXPIRED,

    /** 유효기간이 지난 뒤 도착 → 세션 EXPIRED(TTL_ELAPSED). */
    EXPIRED,

    /** 이미 종료(FAILED·EXPIRED)된 세션의 늦은 결과. 기록만 하고 승인 권한을 만들지 않는다. */
    IGNORED_TERMINAL,
}

/** 승인 시작에서 인증 근거를 쓸 수 없는 이유(외부 노출은 고정 분류 AUTHENTICATION_NOT_VERIFIED만 — 명세 §10.1). */
enum class BindingRejection { NOT_VERIFIED, EXPIRED, ALREADY_BOUND, OTHER_PAYMENT, AMOUNT_MISMATCH }

/**
 * 고객 카드 인증 세션(Aggregate Root, PAYMENT DB — AUTH-CONTRACT-001 §3·§5.3, AUTHN-001 §7).
 *
 * 불변식:
 * - 같은 Payment에 세션 이력은 여러 개일 수 있지만 비종료(CREATED·IN_PROGRESS) 세션은 하나, 귀속 세션도 최대 하나다([create]).
 *   새 세션은 [AuthenticationSessionStart]가 이전 미폐기·미귀속 세션을 대체한 뒤 만든다(ArchUnit).
 * - 인증 근거는 **하나의 논리 승인 operation에만** 귀속한다: [bindTo]는 VERIFIED·미귀속·유효기간 내에서 한 번만, 되돌리지 않는다(D-AUTH-001).
 *   귀속 뒤에는 FAILED·EXPIRED로 바뀌지 않는다.
 * - VERIFIED 세션에 다른 증거·다른 결과 참조가 오면 덮어쓰지 않는다([AuthenticationEvidenceConflictException]).
 * - 단방향 저장 규칙(AUTHN-001 §7.1 H-4): VERIFIED ⇒ 증거·authenticationRef·resultRefDigest·verifiedAt; 증거 ⇒ VERIFIED 또는 EXPIRED(대체·만료된
 *   VERIFIED의 증거는 감사용으로 남는다); resultRefDigest ⇒ VERIFIED·FAILED·EXPIRED(EXPIRED면 증거 있음); failure ⇔ FAILED; expiryCause ⇔ EXPIRED;
 *   IN_PROGRESS·VERIFIED ⇒ providerTransactionRef, CREATED ⇒ 없음.
 */
class AuthenticationSession private constructor(
    val id: AuthenticationSessionId,
    val binding: AuthenticationBinding,
    val correlation: AuthenticationCorrelation,
    val expiresAt: Instant,
    val createdAt: Instant,
) {
    var status: AuthenticationStatus = CREATED
        private set

    /** 기관 거래 참조([start]가 고정). */
    var providerTransactionRef: String? = null
        private set

    var verified: VerifiedAuthentication? = null
        private set

    var verifiedAt: Instant? = null
        private set

    /** 이 세션이 소비한 1회용 결과 참조의 digest(VERIFIED·기관 확정 FAILED). */
    var resultRefDigest: SecretDigest? = null
        private set

    /** VERIFIED 때 서버가 만든 인증 참조(confirm이 세션을 찾는 식별자). */
    var authenticationRef: AuthenticationRef? = null
        private set

    var failure: AuthenticationFailure? = null
        private set

    var expiryCause: AuthenticationExpiryCause? = null
        private set

    var boundAuthorizationId: AuthorizationId? = null
        private set

    /** 비종료(CREATED·IN_PROGRESS). */
    fun isOpen(): Boolean = status == CREATED || status == IN_PROGRESS

    /** 폐기됨(FAILED·EXPIRED). Payment 만료 근거 NOT_TRANSMITTED_AND_SESSION_DISCARDED의 세션 조건이다. */
    fun isDiscarded(): Boolean = status == FAILED || status == EXPIRED

    fun isBound(): Boolean = boundAuthorizationId != null

    /** 결제창이 인증기관 등록을 마쳤다(CREATED → IN_PROGRESS). 기관 거래 참조를 한 번 고정한다. */
    fun start(providerTransactionRef: String) {
        if (status != CREATED) throw InvalidAuthenticationTransitionException(id, status, "start")
        requireProviderRef(providerTransactionRef)
        this.providerTransactionRef = providerTransactionRef
        status = IN_PROGRESS
    }

    /**
     * 서버 검증 결과를 반영한다(AUTHN-001 §7.3 R11의 저수준 전이 — 판정 순서는 [AuthenticationResultCheck]). IN_PROGRESS에서만 VERIFIED가 된다
     * (바인딩 일치·세션 유효기간 내·근거 유효기간 내). 같은 증거·같은 결과 참조 중복은 멱등, 다른 증거·결과 참조는 거부, 이미 종료된 세션의 늦은 결과는 무시한다.
     */
    fun verify(evidence: VerifiedAuthentication, resultRefDigest: SecretDigest, authenticationRef: AuthenticationRef, now: Instant): VerificationOutcome {
        require(resultRefDigest.kind == SecretKind.RESULT_REFERENCE) { "result reference digest expected" }
        when (status) {
            VERIFIED -> {
                if (evidence == verified && resultRefDigest == this.resultRefDigest) return VerificationOutcome.DUPLICATE
                throw AuthenticationEvidenceConflictException(id)
            }
            FAILED, EXPIRED -> return VerificationOutcome.IGNORED_TERMINAL
            CREATED -> throw InvalidAuthenticationTransitionException(id, status, "verify")
            IN_PROGRESS -> {}
        }
        if (!now.isBefore(expiresAt)) {
            status = EXPIRED
            expiryCause = AuthenticationExpiryCause.TTL_ELAPSED
            return VerificationOutcome.EXPIRED
        }
        if (evidence.binding != binding) {
            markFailed(AuthenticationFailure.BINDING_MISMATCH, resultRefDigest)
            return VerificationOutcome.REJECTED_BINDING_MISMATCH
        }
        if (!now.isBefore(evidence.providerValidUntil)) {
            markFailed(AuthenticationFailure.PROVIDER_EXPIRED, resultRefDigest)
            return VerificationOutcome.REJECTED_PROVIDER_EXPIRED
        }
        verified = evidence
        verifiedAt = now
        this.resultRefDigest = resultRefDigest
        this.authenticationRef = authenticationRef
        status = VERIFIED
        return VerificationOutcome.VERIFIED
    }

    /**
     * 인증 실패·사용자 취소 등(CREATED·IN_PROGRESS → FAILED). 기관이 확정한 실패면 소비한 결과 참조 digest를 남긴다. 같은 Payment에서 새 세션을
     * 시작할 수 있다.
     */
    fun fail(reason: AuthenticationFailure, resultRefDigest: SecretDigest? = null) {
        if (!isOpen()) throw InvalidAuthenticationTransitionException(id, status, "fail($reason)")
        require(resultRefDigest == null || resultRefDigest.kind == SecretKind.RESULT_REFERENCE) { "result reference digest expected" }
        markFailed(reason, resultRefDigest)
    }

    /**
     * 만료한다(CREATED·IN_PROGRESS·VERIFIED(미귀속) → EXPIRED, 원인 저장). [AuthenticationExpiryCause.TTL_ELAPSED]는 유효기간이 지나야 한다.
     * 귀속된 세션은 만료하지 않는다. VERIFIED였던 세션의 증거·영수증은 지우지 않는다(감사 — H-4).
     */
    fun expire(cause: AuthenticationExpiryCause, now: Instant) {
        if (isDiscarded() || isBound()) throw InvalidAuthenticationTransitionException(id, status, "expire($cause)")
        if (cause == AuthenticationExpiryCause.TTL_ELAPSED && now.isBefore(expiresAt)) {
            throw InvalidAuthenticationTransitionException(id, status, "expire($cause) before expiresAt")
        }
        status = EXPIRED
        expiryCause = cause
    }

    /**
     * 이 세션의 인증 근거로 [paymentId]·[money]의 승인을 시작할 수 없는 이유(없으면 null). 상태를 바꾸지 않는다. 사용 가능 기간은
     * `min(expiresAt, providerValidUntil)` 전까지다(AUTHN-001 §7.1).
     */
    fun bindingRejection(paymentId: PaymentId, money: Money, now: Instant): BindingRejection? {
        val evidence = verified
        return when {
            binding.paymentId != paymentId -> BindingRejection.OTHER_PAYMENT
            isBound() -> BindingRejection.ALREADY_BOUND
            status != VERIFIED || evidence == null -> BindingRejection.NOT_VERIFIED
            !now.isBefore(minOf(expiresAt, evidence.providerValidUntil)) -> BindingRejection.EXPIRED
            binding.money != money -> BindingRejection.AMOUNT_MISMATCH
            else -> null
        }
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

    private fun markFailed(reason: AuthenticationFailure, resultRefDigest: SecretDigest?) {
        status = FAILED
        failure = reason
        this.resultRefDigest = resultRefDigest
    }

    override fun toString(): String = "AuthenticationSession(id=${id.value}, status=$status, bound=${boundAuthorizationId?.value})"

    companion object {
        private const val PROVIDER_REF_MAX_LENGTH = 128

        private fun requireProviderRef(ref: String) {
            require(ref.isNotEmpty() && ref.length <= PROVIDER_REF_MAX_LENGTH && ref.all { it.code in 0x21..0x7E }) {
                "provider transaction reference must be 1..$PROVIDER_REF_MAX_LENGTH printable ASCII characters"
            }
        }

        /**
         * REQUESTED [payment]에 새 인증 세션을 연다. [sessionsOfPayment]는 같은 Payment의 기존 세션 전부다(비종료·귀속 세션이 있으면 거부).
         * main 코드에서는 [AuthenticationSessionStart]만 부른다(ArchUnit sessionCreateOnlyThroughAuthenticationSessionStart).
         */
        fun create(
            id: AuthenticationSessionId,
            payment: Payment,
            binding: AuthenticationBinding,
            correlation: AuthenticationCorrelation,
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
            return AuthenticationSession(id, binding, correlation, expiresAt, now)
        }

        /** 영속 복원(persistence Mapper 전용). 존재할 수 없는 상태 조합은 거부한다(단방향 규칙 — 클래스 설명, DB CHECK와 같다). */
        fun reconstitute(
            id: AuthenticationSessionId,
            binding: AuthenticationBinding,
            correlation: AuthenticationCorrelation,
            expiresAt: Instant,
            createdAt: Instant,
            status: AuthenticationStatus,
            providerTransactionRef: String?,
            verified: VerifiedAuthentication?,
            verifiedAt: Instant?,
            resultRefDigest: SecretDigest?,
            authenticationRef: AuthenticationRef?,
            failure: AuthenticationFailure?,
            expiryCause: AuthenticationExpiryCause?,
            boundAuthorizationId: AuthorizationId?,
        ): AuthenticationSession {
            val hasEvidence = verified != null
            require(hasEvidence == (authenticationRef != null) && hasEvidence == (verifiedAt != null)) {
                "evidence, authentication reference and verification time exist together"
            }
            require(status != VERIFIED || (hasEvidence && resultRefDigest != null)) { "a VERIFIED session has evidence and its result reference" }
            require(!hasEvidence || status == VERIFIED || status == EXPIRED) { "evidence exists only for VERIFIED or EXPIRED sessions" }
            require(verified == null || verified.binding == binding) { "verified evidence must match the binding" }
            require(resultRefDigest == null || status == VERIFIED || status == FAILED || (status == EXPIRED && hasEvidence)) {
                "a result reference exists only for VERIFIED, FAILED or previously verified EXPIRED sessions"
            }
            require(resultRefDigest == null || resultRefDigest.kind == SecretKind.RESULT_REFERENCE) { "result reference digest expected" }
            require((failure != null) == (status == FAILED)) { "a failure reason exists exactly for FAILED sessions" }
            require((expiryCause != null) == (status == EXPIRED)) { "an expiry cause exists exactly for EXPIRED sessions" }
            require(boundAuthorizationId == null || status == VERIFIED) { "only a VERIFIED session can be bound" }
            when (status) {
                CREATED -> require(providerTransactionRef == null) { "a CREATED session has no provider transaction reference" }
                IN_PROGRESS, VERIFIED -> require(providerTransactionRef != null) { "a started session has its provider transaction reference" }
                FAILED, EXPIRED -> {}
            }
            providerTransactionRef?.let(::requireProviderRef)
            return AuthenticationSession(id, binding, correlation, expiresAt, createdAt).also {
                it.status = status
                it.providerTransactionRef = providerTransactionRef
                it.verified = verified
                it.verifiedAt = verifiedAt
                it.resultRefDigest = resultRefDigest
                it.authenticationRef = authenticationRef
                it.failure = failure
                it.expiryCause = expiryCause
                it.boundAuthorizationId = boundAuthorizationId
            }
        }
    }
}

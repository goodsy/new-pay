package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * authentication_session 행(AUTHN-001 §10.1) — 순수 domain [com.paycore.payment.payment.domain.AuthenticationSession]과 분리된 저장 모양. Mapper가
 * `AuthenticationSession.reconstitute`(존재할 수 없는 조합 거부)로 되돌리고, DB CHECK가 같은 단방향 규칙(H-4)을 한 번 더 지킨다. 바인딩·상관 값·만료 시각·
 * 정책 버전은 생성 시 불변(`updatable = false`). 생성 컬럼(open_payment_id·bound_payment_id)은 매핑하지 않는다. toString에 digest·증거를 넣지 않는다.
 */
@Entity
@Table(name = "authentication_session")
class JpaAuthenticationSessionEntity protected constructor() {
    @field:Id
    @field:Column(name = "authentication_session_id", nullable = false, updatable = false, length = 64)
    lateinit var authenticationSessionId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "checkout_session_id", nullable = false, updatable = false, length = 64)
    lateinit var checkoutSessionId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "plan_kind", nullable = false, updatable = false, length = 12)
    lateinit var planKind: String
        protected set

    @field:Column(name = "installment_months", updatable = false)
    var installmentMonths: Int? = null
        protected set

    @field:Column(name = "card_company", nullable = false, updatable = false, length = 32)
    lateinit var cardCompany: String
        protected set

    @field:Column(name = "status", nullable = false, length = 12)
    lateinit var status: String

    @field:Column(name = "state_digest", nullable = false, updatable = false, columnDefinition = "char(69)")
    lateinit var stateDigest: String
        protected set

    @field:Column(name = "provider_transaction_ref", length = 128)
    var providerTransactionRef: String? = null

    @field:Column(name = "result_ref_digest", columnDefinition = "char(69)")
    var resultRefDigest: String? = null

    @field:Column(name = "failure_reason", length = 24)
    var failureReason: String? = null

    @field:Column(name = "expiry_cause", length = 16)
    var expiryCause: String? = null

    @field:Column(name = "evidence_ref", length = 128)
    var evidenceRef: String? = null

    @field:Column(name = "card_type", length = 8)
    var cardType: String? = null

    @field:Column(name = "issuer_code", length = 32)
    var issuerCode: String? = null

    @field:Column(name = "acquirer_code", length = 32)
    var acquirerCode: String? = null

    @field:Column(name = "provider_code", length = 32)
    var providerCode: String? = null

    @field:Column(name = "provider_valid_until")
    var providerValidUntil: Instant? = null

    @field:Column(name = "verified_at")
    var verifiedAt: Instant? = null

    @field:Column(name = "authentication_ref", columnDefinition = "char(27)")
    var authenticationRef: String? = null

    /** 쓰기 경로는 AUTH-001(confirm 귀속). */
    @field:Column(name = "bound_authorization_id", length = 64)
    var boundAuthorizationId: String? = null

    @field:Column(name = "profile_id", nullable = false, updatable = false, length = 64)
    lateinit var profileId: String
        protected set

    @field:Column(name = "plan_policy_version", nullable = false, updatable = false, length = 64)
    lateinit var planPolicyVersion: String
        protected set

    @field:Column(name = "policy_version", nullable = false, updatable = false, length = 64)
    lateinit var policyVersion: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return authenticationSessionId == (other as JpaAuthenticationSessionEntity).authenticationSessionId
    }

    override fun hashCode(): Int = authenticationSessionId.hashCode()

    override fun toString(): String = "JpaAuthenticationSessionEntity(authenticationSessionId=$authenticationSessionId, status=$status, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            authenticationSessionId: String, paymentId: String, checkoutSessionId: String, environment: String, merchantAccountId: String,
            merchantOrderId: String, amountMinor: Long, currency: String, planKind: String, installmentMonths: Int?, cardCompany: String, stateDigest: String,
            profileId: String, planPolicyVersion: String, policyVersion: String, createdAt: Instant, expiresAt: Instant,
        ): JpaAuthenticationSessionEntity = JpaAuthenticationSessionEntity().also {
            it.authenticationSessionId = authenticationSessionId
            it.paymentId = paymentId
            it.checkoutSessionId = checkoutSessionId
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
            it.amountMinor = amountMinor
            it.currency = currency
            it.planKind = planKind
            it.installmentMonths = installmentMonths
            it.cardCompany = cardCompany
            it.status = "CREATED"
            it.stateDigest = stateDigest
            it.profileId = profileId
            it.planPolicyVersion = planPolicyVersion
            it.policyVersion = policyVersion
            it.createdAt = createdAt
            it.expiresAt = expiresAt
        }
    }
}

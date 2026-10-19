package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant
import java.time.LocalDate

/**
 * payment_authorization 행(AUTH-001 명세 §7.1). 판정 근거·요청 값은 생성 시 고정(`updatable = false`), 바뀌는 것은 상태·종료 근거·version뿐이다.
 * Attempt·관찰·거래는 별도 Entity로 adapter가 INSERT하며 `@OneToMany(cascade)`를 두지 않는다(append-only 사실을 지우는 경로가 없다 — 관례 §9.1).
 */
@Entity
@Table(name = "payment_authorization")
class JpaPaymentAuthorizationEntity protected constructor() {
    @field:Id
    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
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

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, length = 3)
    lateinit var currency: String
        protected set

    @field:Column(name = "plan_kind", nullable = false, updatable = false, length = 12)
    lateinit var planKind: String
        protected set

    @field:Column(name = "installment_months", updatable = false)
    var installmentMonths: Int? = null
        protected set

    @field:Column(name = "authenticated_card_type", nullable = false, updatable = false, length = 8)
    lateinit var authenticatedCardType: String
        protected set

    @field:Column(name = "authentication_session_id", nullable = false, updatable = false, length = 64)
    lateinit var authenticationSessionId: String
        protected set

    @field:Column(name = "entitlement_version", nullable = false, updatable = false, length = 128)
    lateinit var entitlementVersion: String
        protected set

    @field:Column(name = "policy_assignment_version", nullable = false, updatable = false, length = 160)
    lateinit var policyAssignmentVersion: String
        protected set

    @field:Column(name = "route_profile", nullable = false, updatable = false, length = 64)
    lateinit var routeProfile: String
        protected set

    @field:Column(name = "protocol_version", nullable = false, updatable = false, length = 32)
    lateinit var protocolVersion: String
        protected set

    @field:Column(name = "evaluated_at", nullable = false, updatable = false)
    lateinit var evaluatedAt: Instant
        protected set

    @field:Column(name = "plan_policy_version", nullable = false, updatable = false, length = 64)
    lateinit var planPolicyVersion: String
        protected set

    @field:Column(name = "limit_policy_version", nullable = false, updatable = false, length = 64)
    lateinit var limitPolicyVersion: String
        protected set

    @field:Column(name = "limit_business_date", nullable = false, updatable = false)
    lateinit var limitBusinessDate: LocalDate
        protected set

    @field:Column(name = "status", nullable = false, length = 12)
    lateinit var status: String

    @field:Column(name = "failure_evidence", length = 48)
    var failureEvidence: String? = null

    @field:Column(name = "opened_at", nullable = false, updatable = false)
    lateinit var openedAt: Instant
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return authorizationId == (other as JpaPaymentAuthorizationEntity).authorizationId
    }

    override fun hashCode(): Int = authorizationId.hashCode()

    override fun toString(): String = "JpaPaymentAuthorizationEntity(authorizationId=$authorizationId, status=$status, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            authorizationId: String, paymentId: String, environment: String, merchantAccountId: String, merchantOrderId: String, merchantSiteId: String,
            amountMinor: Long, currency: String, planKind: String, installmentMonths: Int?, authenticatedCardType: String, authenticationSessionId: String,
            entitlementVersion: String, policyAssignmentVersion: String, routeProfile: String, protocolVersion: String, evaluatedAt: Instant,
            planPolicyVersion: String, limitPolicyVersion: String, limitBusinessDate: LocalDate, status: String, failureEvidence: String?, openedAt: Instant,
        ): JpaPaymentAuthorizationEntity = JpaPaymentAuthorizationEntity().also {
            it.authorizationId = authorizationId
            it.paymentId = paymentId
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
            it.merchantSiteId = merchantSiteId
            it.amountMinor = amountMinor
            it.currency = currency
            it.planKind = planKind
            it.installmentMonths = installmentMonths
            it.authenticatedCardType = authenticatedCardType
            it.authenticationSessionId = authenticationSessionId
            it.entitlementVersion = entitlementVersion
            it.policyAssignmentVersion = policyAssignmentVersion
            it.routeProfile = routeProfile
            it.protocolVersion = protocolVersion
            it.evaluatedAt = evaluatedAt
            it.planPolicyVersion = planPolicyVersion
            it.limitPolicyVersion = limitPolicyVersion
            it.limitBusinessDate = limitBusinessDate
            it.status = status
            it.failureEvidence = failureEvidence
            it.openedAt = openedAt
        }
    }
}

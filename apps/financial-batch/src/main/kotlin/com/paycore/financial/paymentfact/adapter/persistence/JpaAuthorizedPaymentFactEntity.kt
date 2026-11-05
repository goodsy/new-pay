package com.paycore.financial.paymentfact.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/** payment_fact_authorization 행(append-only 승인 사실 투영). 값 검사는 domain `AuthorizedPaymentFact`가 이미 했다 — Mapper만 이 생성자를 부른다. */
@Entity
@Immutable
@Table(name = "payment_fact_authorization")
class JpaAuthorizedPaymentFactEntity protected constructor() {
    @field:Id
    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 8)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
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

    @field:Column(name = "card_type", nullable = false, updatable = false, length = 8)
    lateinit var cardType: String
        protected set

    @field:Column(name = "issuer_code", nullable = false, updatable = false, length = 32)
    lateinit var issuerCode: String
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "approval_number", nullable = false, updatable = false, length = 32)
    lateinit var approvalNumber: String
        protected set

    @field:Column(name = "provider_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var providerTransactionId: String
        protected set

    @field:Column(name = "institution_transaction_date", nullable = false, updatable = false)
    lateinit var institutionTransactionDate: LocalDate
        protected set

    @field:Column(name = "institution_business_date", updatable = false)
    var institutionBusinessDate: LocalDate? = null
        protected set

    @field:Column(name = "approved_at", nullable = false, updatable = false)
    lateinit var approvedAt: Instant
        protected set

    @field:Column(name = "policy_assignment_version", nullable = false, updatable = false, length = 160)
    lateinit var policyAssignmentVersion: String
        protected set

    @field:Column(name = "entitlement_version", nullable = false, updatable = false, length = 128)
    lateinit var entitlementVersion: String
        protected set

    @field:Column(name = "source_event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var sourceEventId: String
        protected set

    @field:Column(name = "effect_key", nullable = false, updatable = false, length = 128)
    lateinit var effectKey: String
        protected set

    @field:Column(name = "received_at", nullable = false, updatable = false)
    lateinit var receivedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAuthorizedPaymentFactEntity(transactionId=$transactionId, paymentId=$paymentId)"

    companion object {
        /** Mapper 전용: 빈 행을 만든 뒤 [fill]로 모든 열을 채운다(채우지 않은 lateinit 열이 있으면 persist 전에 실패한다). */
        internal fun blank(): JpaAuthorizedPaymentFactEntity = JpaAuthorizedPaymentFactEntity()
    }

    @Suppress("LongParameterList")
    internal fun fill(
        transactionId: String, paymentId: String, authorizationId: String, environment: String, merchantAccountId: String, merchantSiteId: String,
        merchantOrderId: String, amountMinor: Long, currency: String, planKind: String, installmentMonths: Int?, cardType: String, issuerCode: String,
        acquirerCode: String, approvalNumber: String, providerTransactionId: String, institutionTransactionDate: LocalDate, institutionBusinessDate: LocalDate?,
        approvedAt: Instant, policyAssignmentVersion: String, entitlementVersion: String, sourceEventId: String, effectKey: String, receivedAt: Instant,
    ): JpaAuthorizedPaymentFactEntity = also {
        this.transactionId = transactionId
        this.paymentId = paymentId
        this.authorizationId = authorizationId
        this.environment = environment
        this.merchantAccountId = merchantAccountId
        this.merchantSiteId = merchantSiteId
        this.merchantOrderId = merchantOrderId
        this.amountMinor = amountMinor
        this.currency = currency
        this.planKind = planKind
        this.installmentMonths = installmentMonths
        this.cardType = cardType
        this.issuerCode = issuerCode
        this.acquirerCode = acquirerCode
        this.approvalNumber = approvalNumber
        this.providerTransactionId = providerTransactionId
        this.institutionTransactionDate = institutionTransactionDate
        this.institutionBusinessDate = institutionBusinessDate
        this.approvedAt = approvedAt
        this.policyAssignmentVersion = policyAssignmentVersion
        this.entitlementVersion = entitlementVersion
        this.sourceEventId = sourceEventId
        this.effectKey = effectKey
        this.receivedAt = receivedAt
    }
}

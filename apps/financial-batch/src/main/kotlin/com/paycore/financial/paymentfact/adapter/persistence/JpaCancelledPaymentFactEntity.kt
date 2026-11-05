package com.paycore.financial.paymentfact.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * payment_fact_cancellation 행(append-only 취소 사실 투영). 원승인으로 가는 FK·`@ManyToOne` 연관이 없다: 원승인보다 먼저 온 취소도 보류로 남기기 때문이다
 * (D-ACQ-002). 원승인은 Store가 같은 Payment 이력에서 transaction_id로 찾는다.
 */
@Entity
@Immutable
@Table(name = "payment_fact_cancellation")
class JpaCancelledPaymentFactEntity protected constructor() {
    @field:Id
    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "original_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var originalTransactionId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "cancellation_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationId: String
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

    @field:Column(name = "scope", nullable = false, updatable = false, length = 8)
    lateinit var scope: String
        protected set

    @field:Column(name = "authorized_amount_minor", nullable = false, updatable = false)
    var authorizedAmountMinor: Long = 0
        protected set

    @field:Column(name = "return_sequence", nullable = false, updatable = false)
    var returnSequence: Int = 0
        protected set

    @field:Column(name = "cumulative_returned_minor", nullable = false, updatable = false)
    var cumulativeReturnedMinor: Long = 0
        protected set

    @field:Column(name = "return_status_after", nullable = false, updatable = false, length = 24)
    lateinit var returnStatusAfter: String
        protected set

    @field:Column(name = "cancel_approval_number", updatable = false, length = 32)
    var cancelApprovalNumber: String? = null
        protected set

    @field:Column(name = "provider_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var providerTransactionId: String
        protected set

    @field:Column(name = "original_provider_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var originalProviderTransactionId: String
        protected set

    @field:Column(name = "institution_transaction_date", nullable = false, updatable = false)
    lateinit var institutionTransactionDate: LocalDate
        protected set

    @field:Column(name = "institution_business_date", updatable = false)
    var institutionBusinessDate: LocalDate? = null
        protected set

    @field:Column(name = "cancelled_at", nullable = false, updatable = false)
    lateinit var cancelledAt: Instant
        protected set

    @field:Column(name = "policy_assignment_version", nullable = false, updatable = false, length = 160)
    lateinit var policyAssignmentVersion: String
        protected set

    @field:Column(name = "entitlement_version", nullable = false, updatable = false, length = 128)
    lateinit var entitlementVersion: String
        protected set

    @field:Column(name = "initiator_kind", nullable = false, updatable = false, length = 8)
    lateinit var initiatorKind: String
        protected set

    @field:Column(name = "original_plan_kind", nullable = false, updatable = false, length = 12)
    lateinit var originalPlanKind: String
        protected set

    @field:Column(name = "original_installment_months", updatable = false)
    var originalInstallmentMonths: Int? = null
        protected set

    @field:Column(name = "original_card_type", nullable = false, updatable = false, length = 8)
    lateinit var originalCardType: String
        protected set

    @field:Column(name = "original_known_at_receipt", nullable = false, updatable = false)
    var originalKnownAtReceipt: Boolean = false
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

    override fun toString(): String = "JpaCancelledPaymentFactEntity(transactionId=$transactionId, originalTransactionId=$originalTransactionId)"

    companion object {
        /** Mapper 전용: 빈 행을 만든 뒤 [fill]로 모든 열을 채운다. */
        internal fun blank(): JpaCancelledPaymentFactEntity = JpaCancelledPaymentFactEntity()
    }

    @Suppress("LongParameterList")
    internal fun fill(
        transactionId: String, originalTransactionId: String, paymentId: String, authorizationId: String, cancellationId: String, environment: String,
        merchantAccountId: String, merchantSiteId: String, merchantOrderId: String, amountMinor: Long, currency: String, scope: String,
        authorizedAmountMinor: Long, returnSequence: Int, cumulativeReturnedMinor: Long, returnStatusAfter: String, cancelApprovalNumber: String?,
        providerTransactionId: String, originalProviderTransactionId: String, institutionTransactionDate: LocalDate, institutionBusinessDate: LocalDate?,
        cancelledAt: Instant, policyAssignmentVersion: String, entitlementVersion: String, initiatorKind: String, originalPlanKind: String,
        originalInstallmentMonths: Int?, originalCardType: String, originalKnownAtReceipt: Boolean, sourceEventId: String, effectKey: String, receivedAt: Instant,
    ): JpaCancelledPaymentFactEntity = also {
        this.transactionId = transactionId
        this.originalTransactionId = originalTransactionId
        this.paymentId = paymentId
        this.authorizationId = authorizationId
        this.cancellationId = cancellationId
        this.environment = environment
        this.merchantAccountId = merchantAccountId
        this.merchantSiteId = merchantSiteId
        this.merchantOrderId = merchantOrderId
        this.amountMinor = amountMinor
        this.currency = currency
        this.scope = scope
        this.authorizedAmountMinor = authorizedAmountMinor
        this.returnSequence = returnSequence
        this.cumulativeReturnedMinor = cumulativeReturnedMinor
        this.returnStatusAfter = returnStatusAfter
        this.cancelApprovalNumber = cancelApprovalNumber
        this.providerTransactionId = providerTransactionId
        this.originalProviderTransactionId = originalProviderTransactionId
        this.institutionTransactionDate = institutionTransactionDate
        this.institutionBusinessDate = institutionBusinessDate
        this.cancelledAt = cancelledAt
        this.policyAssignmentVersion = policyAssignmentVersion
        this.entitlementVersion = entitlementVersion
        this.initiatorKind = initiatorKind
        this.originalPlanKind = originalPlanKind
        this.originalInstallmentMonths = originalInstallmentMonths
        this.originalCardType = originalCardType
        this.originalKnownAtReceipt = originalKnownAtReceipt
        this.sourceEventId = sourceEventId
        this.effectKey = effectKey
        this.receivedAt = receivedAt
    }
}

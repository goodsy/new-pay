package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * payment_cancellation 행(CANCEL-001 명세 §8.1). 대상·금액·개시자·원승인 정책 버전·Capability 버전은 생성 뒤 바뀌지 않고(`updatable = false`), 상태·종료 근거·사건 수만
 * 바뀐다(잠가 읽은 행의 변경 감지 + `@Version`). 생성 열 `in_flight_payment_id`는 매핑하지 않는다(DB가 계산 — Payment당 진행 중 하나).
 * 운영자 사유(`operatorReason`)는 내부 감사 값이라 [toString]에 넣지 않는다.
 */
@Entity
@Table(name = "payment_cancellation")
class JpaPaymentCancellationEntity protected constructor() {
    @field:Id
    @field:Column(name = "cancellation_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "original_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var originalTransactionId: String
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

    @field:Column(name = "currency", nullable = false, updatable = false, length = 3)
    lateinit var currency: String
        protected set

    @field:Column(name = "scope", nullable = false, updatable = false, length = 8)
    lateinit var scope: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "failure_evidence", length = 48)
    var failureEvidence: String? = null

    @field:Column(name = "incident_count", nullable = false)
    var incidentCount: Int = 0

    @field:Column(name = "initiator_kind", nullable = false, updatable = false, length = 8)
    lateinit var initiatorKind: String
        protected set

    @field:Column(name = "merchant_cancel_id", updatable = false, length = 64)
    var merchantCancelId: String? = null
        protected set

    @field:Column(name = "approval_id", updatable = false, length = 64)
    var approvalId: String? = null
        protected set

    @field:Column(name = "requested_by", updatable = false, length = 64)
    var requestedBy: String? = null
        protected set

    @field:Column(name = "approved_by", updatable = false, length = 64)
    var approvedBy: String? = null
        protected set

    @field:Column(name = "operator_reason", updatable = false, length = 500)
    var operatorReason: String? = null
        protected set

    @field:Column(name = "purpose_kind", updatable = false, length = 40)
    var purposeKind: String? = null
        protected set

    @field:Column(name = "purpose_ref", updatable = false, length = 200)
    var purposeRef: String? = null
        protected set

    @field:Column(name = "approved_target_fingerprint", updatable = false, length = 80)
    var approvedTargetFingerprint: String? = null
        protected set

    @field:Column(name = "original_provider_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var originalProviderTransactionId: String
        protected set

    @field:Column(name = "policy_assignment_version", nullable = false, updatable = false, length = 160)
    lateinit var policyAssignmentVersion: String
        protected set

    @field:Column(name = "entitlement_version", nullable = false, updatable = false, length = 128)
    lateinit var entitlementVersion: String
        protected set

    @field:Column(name = "cancel_capability_version", nullable = false, updatable = false, length = 64)
    lateinit var cancelCapabilityVersion: String
        protected set

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
        return cancellationId == (other as JpaPaymentCancellationEntity).cancellationId
    }

    override fun hashCode(): Int = cancellationId.hashCode()

    override fun toString(): String = "JpaPaymentCancellationEntity(cancellationId=$cancellationId, status=$status, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            cancellationId: String, paymentId: String, authorizationId: String, originalTransactionId: String, environment: String, merchantAccountId: String,
            merchantOrderId: String, amountMinor: Long, currency: String, scope: String, status: String, failureEvidence: String?, incidentCount: Int,
            initiatorKind: String, merchantCancelId: String?, approvalId: String?, requestedBy: String?, approvedBy: String?, operatorReason: String?,
            purposeKind: String?, purposeRef: String?, approvedTargetFingerprint: String?, originalProviderTransactionId: String, policyAssignmentVersion: String,
            entitlementVersion: String, cancelCapabilityVersion: String, openedAt: Instant,
        ): JpaPaymentCancellationEntity = JpaPaymentCancellationEntity().also {
            it.cancellationId = cancellationId
            it.paymentId = paymentId
            it.authorizationId = authorizationId
            it.originalTransactionId = originalTransactionId
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
            it.amountMinor = amountMinor
            it.currency = currency
            it.scope = scope
            it.status = status
            it.failureEvidence = failureEvidence
            it.incidentCount = incidentCount
            it.initiatorKind = initiatorKind
            it.merchantCancelId = merchantCancelId
            it.approvalId = approvalId
            it.requestedBy = requestedBy
            it.approvedBy = approvedBy
            it.operatorReason = operatorReason
            it.purposeKind = purposeKind
            it.purposeRef = purposeRef
            it.approvedTargetFingerprint = approvedTargetFingerprint
            it.originalProviderTransactionId = originalProviderTransactionId
            it.policyAssignmentVersion = policyAssignmentVersion
            it.entitlementVersion = entitlementVersion
            it.cancelCapabilityVersion = cancelCapabilityVersion
            it.openedAt = openedAt
        }
    }
}

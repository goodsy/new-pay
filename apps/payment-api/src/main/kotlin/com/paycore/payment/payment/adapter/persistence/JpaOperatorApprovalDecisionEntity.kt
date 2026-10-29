package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * operator_approval_decision 행(ADMIN-PAYMENT-OPS-001 §4.4.4, D-OPS-005 — approvalId별 최초 확정 결정). `@Immutable` + `persist`만(merge·save 금지) + runtime 권한 SELECT·INSERT(1142) 두 겹.
 * `recorded_at`은 DB가 INSERT 문 실행 시각으로 채운다(`insertable=false, updatable=false` — 대사 안정 watermark 기준, 앱 시각 아님). equals/hashCode는 approvalId(UNIQUE 업무 키)만.
 */
@Entity
@Immutable
@Table(name = "operator_approval_decision")
class JpaOperatorApprovalDecisionEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Long? = null
        protected set

    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 64)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "decision", nullable = false, updatable = false, length = 16)
    lateinit var decision: String
        protected set

    @field:Column(name = "rejection_code", updatable = false, length = 48)
    var rejectionCode: String? = null
        protected set

    @field:Column(name = "cancellation_id", updatable = false, length = 64)
    var cancellationId: String? = null
        protected set

    @field:Column(name = "content_hash", nullable = false, updatable = false, length = 69, columnDefinition = "char(69)")
    lateinit var contentHash: String
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, length = 3, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    lateinit var requestedBy: String
        protected set

    @field:Column(name = "approved_by", nullable = false, updatable = false, length = 64)
    lateinit var approvedBy: String
        protected set

    @field:Column(name = "approved_target_fingerprint", nullable = false, updatable = false, length = 69)
    lateinit var approvedTargetFingerprint: String
        protected set

    @field:Column(name = "approved_at", nullable = false, updatable = false)
    lateinit var approvedAt: Instant
        protected set

    @field:Column(name = "execute_before", nullable = false, updatable = false)
    lateinit var executeBefore: Instant
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    @field:Column(name = "recorded_at", insertable = false, updatable = false)
    var recordedAt: Instant? = null
        protected set

    @field:Column(name = "origin", nullable = false, updatable = false, length = 24)
    lateinit var origin: String
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return approvalId == (other as JpaOperatorApprovalDecisionEntity).approvalId
    }

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaOperatorApprovalDecisionEntity(approvalId=$approvalId, decision=$decision, code=$rejectionCode)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String, paymentId: String, decision: String, rejectionCode: String?, cancellationId: String?, contentHash: String, amountMinor: Long, currency: String,
            requestedBy: String, approvedBy: String, approvedTargetFingerprint: String, approvedAt: Instant, executeBefore: Instant, decidedAt: Instant, origin: String,
        ) = JpaOperatorApprovalDecisionEntity().also {
            it.approvalId = approvalId
            it.paymentId = paymentId
            it.decision = decision
            it.rejectionCode = rejectionCode
            it.cancellationId = cancellationId
            it.contentHash = contentHash
            it.amountMinor = amountMinor
            it.currency = currency
            it.requestedBy = requestedBy
            it.approvedBy = approvedBy
            it.approvedTargetFingerprint = approvedTargetFingerprint
            it.approvedAt = approvedAt
            it.executeBefore = executeBefore
            it.decidedAt = decidedAt
            it.origin = origin
        }
    }
}

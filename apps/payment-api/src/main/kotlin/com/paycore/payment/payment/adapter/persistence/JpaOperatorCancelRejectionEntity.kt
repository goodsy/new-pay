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

/** operator_cancel_rejection 행(D-CANCEL1-006 append-only 감사). `@Immutable` + runtime 권한 SELECT·INSERT(1142) 두 겹. */
@Entity
@Immutable
@Table(name = "operator_cancel_rejection")
class JpaOperatorCancelRejectionEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 64)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    lateinit var requestedBy: String
        protected set

    @field:Column(name = "approved_by", nullable = false, updatable = false, length = 64)
    lateinit var approvedBy: String
        protected set

    @field:Column(name = "rejection_reason", nullable = false, updatable = false, length = 48)
    lateinit var rejectionReason: String
        protected set

    @field:Column(name = "rejected_at", nullable = false, updatable = false)
    lateinit var rejectedAt: Instant
        protected set

    private fun naturalKey() = listOf(approvalId, paymentId, rejectionReason)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return naturalKey() == (other as JpaOperatorCancelRejectionEntity).naturalKey()
    }

    override fun hashCode(): Int = naturalKey().hashCode()

    override fun toString(): String = "JpaOperatorCancelRejectionEntity(approvalId=$approvalId, reason=$rejectionReason)"

    companion object {
        fun newRow(approvalId: String, paymentId: String, requestedBy: String, approvedBy: String, reason: String, at: Instant) =
            JpaOperatorCancelRejectionEntity().also {
                it.approvalId = approvalId
                it.paymentId = paymentId
                it.requestedBy = requestedBy
                it.approvedBy = approvedBy
                it.rejectionReason = reason
                it.rejectedAt = at
            }
    }
}

package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** admin_ops_reconciliation_finding 행(INSERT-only — `@Immutable` + runtime SELECT, INSERT만, UNIQUE(kind, approval_id)). persist만. */
@Entity
@Immutable
@Table(name = "admin_ops_reconciliation_finding")
class JpaReconciliationFindingEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "finding_id", nullable = false, updatable = false)
    var findingId: Long? = null
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 32)
    lateinit var kind: String
        protected set

    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 64)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "payment_ref", updatable = false, length = 64)
    var paymentRef: String? = null
        protected set

    @field:Column(name = "detail_code", updatable = false, length = 200)
    var detailCode: String? = null
        protected set

    @field:Column(name = "found_at", nullable = false, updatable = false)
    lateinit var foundAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is JpaReconciliationFindingEntity && findingId != null && findingId == other.findingId)

    override fun hashCode(): Int = findingId?.hashCode() ?: 0

    override fun toString(): String = "JpaReconciliationFindingEntity(kind=$kind, approvalId=$approvalId)"

    companion object {
        fun newRow(kind: String, approvalId: String, paymentRef: String?, detailCode: String?, foundAt: Instant) = JpaReconciliationFindingEntity().also {
            it.kind = kind
            it.approvalId = approvalId
            it.paymentRef = paymentRef
            it.detailCode = detailCode
            it.foundAt = foundAt
        }
    }
}

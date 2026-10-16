package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/** merchant_account_site_assignment 행. 이번 범위는 INSERT만(종료·중지는 MASTER-LIFECYCLE-001이 UPDATE 권한과 함께 추가). */
@Entity
@Table(name = "merchant_account_site_assignment")
class JpaSiteAssignmentEntity protected constructor() {
    @field:Id
    @field:Column(name = "assignment_id", nullable = false, updatable = false, length = 64)
    lateinit var assignmentId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "role", nullable = false, updatable = false, length = 16)
    lateinit var role: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to")
    var effectiveTo: Instant? = null
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && assignmentId == (other as JpaSiteAssignmentEntity).assignmentId)

    override fun hashCode(): Int = assignmentId.hashCode()

    override fun toString(): String = "JpaSiteAssignmentEntity(assignmentId=$assignmentId, role=$role, status=$status)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            accountId: String,
            siteId: String,
            merchantId: String,
            role: String,
            status: String,
            from: Instant,
            to: Instant?,
            at: Instant,
            by: String,
        ) = JpaSiteAssignmentEntity().also {
            it.assignmentId = id
            it.merchantAccountId = accountId
            it.merchantSiteId = siteId
            it.merchantId = merchantId
            it.role = role
            it.status = status
            it.effectiveFrom = from
            it.effectiveTo = to
            it.createdAt = at
            it.createdBy = by
        }
    }
}

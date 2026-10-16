package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** policy_assignment 행. 종료는 변경 감지 + `@Version`. */
@Entity
@Table(name = "policy_assignment")
class JpaPolicyAssignmentEntity protected constructor() {
    @field:Id
    @field:Column(name = "assignment_id", nullable = false, updatable = false, length = 64)
    lateinit var assignmentId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "agreement_id", nullable = false, updatable = false, length = 64)
    lateinit var agreementId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currencyCode: String
        protected set

    @field:Column(name = "policy_kind", nullable = false, updatable = false, length = 24)
    lateinit var policyKind: String
        protected set

    @field:Column(name = "policy_version_id", nullable = false, updatable = false, length = 64)
    lateinit var policyVersionId: String
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to")
    var effectiveTo: Instant? = null

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "ended_at")
    var endedAt: Instant? = null

    @field:Column(name = "ended_by", length = 64)
    var endedBy: String? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && assignmentId == (other as JpaPolicyAssignmentEntity).assignmentId)

    override fun hashCode(): Int = assignmentId.hashCode()

    override fun toString(): String = "JpaPolicyAssignmentEntity(id=$assignmentId, kind=$policyKind, currency=$currencyCode)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            merchantId: String,
            agreementId: String,
            accountId: String,
            currency: String,
            kind: String,
            policyVersionId: String,
            from: Instant,
            to: Instant?,
            reason: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaPolicyAssignmentEntity().also {
            it.assignmentId = id
            it.merchantId = merchantId
            it.agreementId = agreementId
            it.merchantAccountId = accountId
            it.currencyCode = currency
            it.policyKind = kind
            it.policyVersionId = policyVersionId
            it.effectiveFrom = from
            it.effectiveTo = to
            it.reason = reason
            it.createdAt = at
            it.createdBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

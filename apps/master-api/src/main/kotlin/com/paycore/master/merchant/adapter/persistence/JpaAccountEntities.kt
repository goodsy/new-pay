package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** merchant_account 행(MID). 소속·발급 근거는 updatable=false(초기 "근거 Revision 보존"). */
@Entity
@Table(name = "merchant_account")
class JpaMerchantAccountEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "issuing_contract_id", nullable = false, updatable = false, length = 64)
    lateinit var issuingContractId: String
        protected set

    @field:Column(name = "issuing_agreement_id", nullable = false, updatable = false, length = 64)
    lateinit var issuingAgreementId: String
        protected set

    @field:Column(name = "issuing_revision_no", nullable = false, updatable = false)
    var issuingRevisionNo: Int = 0
        protected set

    @field:Column(name = "lifecycle", nullable = false, length = 16)
    lateinit var lifecycle: String
        protected set

    @field:Column(name = "issued_at", nullable = false, updatable = false)
    lateinit var issuedAt: Instant
        protected set

    @field:Column(name = "issued_by", nullable = false, updatable = false, length = 64)
    lateinit var issuedBy: String
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantAccountId == (other as JpaMerchantAccountEntity).merchantAccountId)

    override fun hashCode(): Int = merchantAccountId.hashCode()

    override fun toString(): String = "JpaMerchantAccountEntity(merchantAccountId=$merchantAccountId, lifecycle=$lifecycle)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            merchantId: String,
            businessId: String,
            contractId: String,
            agreementId: String,
            revisionNo: Int,
            lifecycle: String,
            at: Instant,
            by: String,
        ) = JpaMerchantAccountEntity().also {
            it.merchantAccountId = id
            it.merchantId = merchantId
            it.businessId = businessId
            it.issuingContractId = contractId
            it.issuingAgreementId = agreementId
            it.issuingRevisionNo = revisionNo
            it.lifecycle = lifecycle
            it.issuedAt = at
            it.issuedBy = by
        }
    }
}

/** default_designation 행(Merchant당 하나 — PK). 교체 경로 없음(Q-MD-07). */
@Entity
@Table(name = "default_designation")
class JpaDefaultDesignationEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "designated_at", nullable = false, updatable = false)
    lateinit var designatedAt: Instant
        protected set

    @field:Column(name = "designated_by", nullable = false, updatable = false, length = 64)
    lateinit var designatedBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantId == (other as JpaDefaultDesignationEntity).merchantId)

    override fun hashCode(): Int = merchantId.hashCode()

    override fun toString(): String = "JpaDefaultDesignationEntity(merchantId=$merchantId, merchantAccountId=$merchantAccountId)"

    companion object {
        fun newRow(merchantId: String, accountId: String, at: Instant, by: String) = JpaDefaultDesignationEntity().also {
            it.merchantId = merchantId
            it.merchantAccountId = accountId
            it.designatedAt = at
            it.designatedBy = by
        }
    }
}

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

/** payment_method_activation 행(최소 저장, 상태 없음 — Q-MD-09). */
@Entity
@Table(name = "payment_method_activation")
class JpaPaymentMethodActivationEntity protected constructor() {
    @field:Id
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    lateinit var paymentMethod: String
        protected set

    @field:Column(name = "registered_at", nullable = false, updatable = false)
    lateinit var registeredAt: Instant
        protected set

    @field:Column(name = "registered_by", nullable = false, updatable = false, length = 64)
    lateinit var registeredBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && activationId == (other as JpaPaymentMethodActivationEntity).activationId)

    override fun hashCode(): Int = activationId.hashCode()

    override fun toString(): String = "JpaPaymentMethodActivationEntity(activationId=$activationId, paymentMethod=$paymentMethod)"

    companion object {
        fun newRow(id: String, accountId: String, method: String, at: Instant, by: String) = JpaPaymentMethodActivationEntity().also {
            it.activationId = id
            it.merchantAccountId = accountId
            it.paymentMethod = method
            it.registeredAt = at
            it.registeredBy = by
        }
    }
}

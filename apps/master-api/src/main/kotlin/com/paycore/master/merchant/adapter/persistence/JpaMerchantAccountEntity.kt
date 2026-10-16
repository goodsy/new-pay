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

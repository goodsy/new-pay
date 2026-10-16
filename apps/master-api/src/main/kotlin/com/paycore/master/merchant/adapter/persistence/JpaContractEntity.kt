package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** contract 행(계약 root). 자식(Revision·Scope Site·승인·CSA)은 별도 Entity로 INSERT만 한다(append-only). */
@Entity
@Table(name = "contract")
class JpaContractEntity protected constructor() {
    @field:Id
    @field:Column(name = "contract_id", nullable = false, updatable = false, length = 64)
    lateinit var contractId: String
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    /** 대상 Merchant(모든 Revision 공통 — D-MD-013). 바뀌지 않는다. */
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && contractId == (other as JpaContractEntity).contractId)

    override fun hashCode(): Int = contractId.hashCode()

    override fun toString(): String = "JpaContractEntity(contractId=$contractId, version=$version)"

    companion object {
        fun newRow(id: String, businessId: String, merchantId: String, at: Instant, by: String) = JpaContractEntity().also {
            it.contractId = id
            it.businessId = businessId
            it.merchantId = merchantId
            it.createdAt = at
            it.createdBy = by
        }
    }
}

package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable

@Embeddable
class ScopeSiteKey protected constructor() : Serializable {
    @field:Column(name = "contract_id", nullable = false, updatable = false, length = 64)
    lateinit var contractId: String
        protected set

    @field:Column(name = "revision_no", nullable = false, updatable = false)
    var revisionNo: Int = 0
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ScopeSiteKey && contractId == other.contractId && revisionNo == other.revisionNo && merchantSiteId == other.merchantSiteId)

    override fun hashCode(): Int = (31 * contractId.hashCode() + revisionNo) * 31 + merchantSiteId.hashCode()

    override fun toString(): String = "ScopeSiteKey($contractId#$revisionNo/$merchantSiteId)"

    companion object {
        fun of(contractId: String, revisionNo: Int, merchantSiteId: String) = ScopeSiteKey().also {
            it.contractId = contractId
            it.revisionNo = revisionNo
            it.merchantSiteId = merchantSiteId
        }
    }
}

/** contract_revision_scope_site 행(append-only). */
@Entity
@Table(name = "contract_revision_scope_site")
class JpaContractRevisionScopeSiteEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: ScopeSiteKey
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaContractRevisionScopeSiteEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaContractRevisionScopeSiteEntity(key=$key)"

    companion object {
        fun newRow(key: ScopeSiteKey, merchantId: String) = JpaContractRevisionScopeSiteEntity().also {
            it.key = key
            it.merchantId = merchantId
        }
    }
}

package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.io.Serializable
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

/** (contract_id, revision_no) 복합 키. 값 객체라 equals/hashCode를 두 필드로 정의한다(payment-api 키와 같은 모양). */
@Embeddable
class RevisionKey protected constructor() : Serializable {
    @field:Column(name = "contract_id", nullable = false, updatable = false, length = 64)
    lateinit var contractId: String
        protected set

    @field:Column(name = "revision_no", nullable = false, updatable = false)
    var revisionNo: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other is RevisionKey && contractId == other.contractId && revisionNo == other.revisionNo)

    override fun hashCode(): Int = 31 * contractId.hashCode() + revisionNo

    override fun toString(): String = "RevisionKey($contractId#$revisionNo)"

    companion object {
        fun of(contractId: String, revisionNo: Int) = RevisionKey().also {
            it.contractId = contractId
            it.revisionNo = revisionNo
        }
    }
}

/** contract_revision 행(append-only — 내용 불변, D-MD-004). 모든 컬럼 updatable=false. */
@Entity
@Table(name = "contract_revision")
class JpaContractRevisionEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: RevisionKey
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "scope_kind", nullable = false, updatable = false, length = 16)
    lateinit var scopeKind: String
        protected set

    @field:Column(name = "scope_merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var scopeMerchantId: String
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to", updatable = false)
    var effectiveTo: Instant? = null
        protected set

    @field:Column(name = "registered_at", nullable = false, updatable = false)
    lateinit var registeredAt: Instant
        protected set

    @field:Column(name = "registered_by", nullable = false, updatable = false, length = 64)
    lateinit var registeredBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaContractRevisionEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaContractRevisionEntity(key=$key, scopeKind=$scopeKind)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            key: RevisionKey,
            businessId: String,
            scopeKind: String,
            scopeMerchantId: String,
            effectiveFrom: Instant,
            effectiveTo: Instant?,
            at: Instant,
            by: String,
        ) = JpaContractRevisionEntity().also {
            it.key = key
            it.businessId = businessId
            it.scopeKind = scopeKind
            it.scopeMerchantId = scopeMerchantId
            it.effectiveFrom = effectiveFrom
            it.effectiveTo = effectiveTo
            it.registeredAt = at
            it.registeredBy = by
        }
    }
}

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

/** contract_revision_approval 행(append-only 확정 사실, Revision당 하나 — PK). */
@Entity
@Table(name = "contract_revision_approval")
class JpaContractRevisionApprovalEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: RevisionKey
        protected set

    @field:Column(name = "approved_at", nullable = false, updatable = false)
    lateinit var approvedAt: Instant
        protected set

    @field:Column(name = "approved_by", nullable = false, updatable = false, length = 64)
    lateinit var approvedBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaContractRevisionApprovalEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaContractRevisionApprovalEntity(key=$key)"

    companion object {
        fun newRow(key: RevisionKey, at: Instant, by: String) = JpaContractRevisionApprovalEntity().also {
            it.key = key
            it.approvedAt = at
            it.approvedBy = by
        }
    }
}

/** contract_service_agreement 행(INSERT만). */
@Entity
@Table(name = "contract_service_agreement")
class JpaContractServiceAgreementEntity protected constructor() {
    @field:Id
    @field:Column(name = "agreement_id", nullable = false, updatable = false, length = 64)
    lateinit var agreementId: String
        protected set

    @field:Column(name = "contract_id", nullable = false, updatable = false, length = 64)
    lateinit var contractId: String
        protected set

    @field:Column(name = "service_kind", nullable = false, updatable = false, length = 32)
    lateinit var serviceKind: String
        protected set

    @field:Column(name = "added_at", nullable = false, updatable = false)
    lateinit var addedAt: Instant
        protected set

    @field:Column(name = "added_by", nullable = false, updatable = false, length = 64)
    lateinit var addedBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && agreementId == (other as JpaContractServiceAgreementEntity).agreementId)

    override fun hashCode(): Int = agreementId.hashCode()

    override fun toString(): String = "JpaContractServiceAgreementEntity(agreementId=$agreementId, serviceKind=$serviceKind)"

    companion object {
        fun newRow(id: String, contractId: String, serviceKind: String, at: Instant, by: String) = JpaContractServiceAgreementEntity().also {
            it.agreementId = id
            it.contractId = contractId
            it.serviceKind = serviceKind
            it.addedAt = at
            it.addedBy = by
        }
    }
}

package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant

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

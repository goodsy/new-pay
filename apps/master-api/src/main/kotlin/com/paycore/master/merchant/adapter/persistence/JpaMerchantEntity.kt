package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

@Entity
@Table(name = "merchant")
class JpaMerchantEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "business_id", nullable = false, updatable = false, length = 64)
    lateinit var businessId: String
        protected set

    @field:Column(name = "display_name", nullable = false, length = 100)
    lateinit var displayName: String
        protected set

    @field:Column(name = "lifecycle", nullable = false, length = 16)
    lateinit var lifecycle: String
        protected set

    /** 업무 단조 값(결제 허용 판정 입력 변경 횟수). JPA version과 별개다. */
    @field:Column(name = "entitlement_revision", nullable = false)
    var entitlementRevision: Long = 0

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
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantId == (other as JpaMerchantEntity).merchantId)

    override fun hashCode(): Int = merchantId.hashCode()

    override fun toString(): String = "JpaMerchantEntity(merchantId=$merchantId, entitlementRevision=$entitlementRevision, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(id: String, businessId: String, displayName: String, lifecycle: String, entitlementRevision: Long, at: Instant, by: String) =
            JpaMerchantEntity().also {
                it.merchantId = id
                it.businessId = businessId
                it.displayName = displayName
                it.lifecycle = lifecycle
                it.entitlementRevision = entitlementRevision
                it.createdAt = at
                it.createdBy = by
            }
    }
}

package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

@Entity
@Table(name = "merchant_site")
class JpaMerchantSiteEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "channel_type", nullable = false, updatable = false, length = 8)
    lateinit var channelType: String
        protected set

    @field:Column(name = "channel_identifier", nullable = false, updatable = false, length = 253)
    lateinit var channelIdentifier: String
        protected set

    @field:Column(name = "lifecycle", nullable = false, length = 16)
    lateinit var lifecycle: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantSiteId == (other as JpaMerchantSiteEntity).merchantSiteId)

    override fun hashCode(): Int = merchantSiteId.hashCode()

    override fun toString(): String = "JpaMerchantSiteEntity(merchantSiteId=$merchantSiteId, lifecycle=$lifecycle)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(id: String, merchantId: String, channelType: String, channelIdentifier: String, lifecycle: String, at: Instant, by: String) =
            JpaMerchantSiteEntity().also {
                it.merchantSiteId = id
                it.merchantId = merchantId
                it.channelType = channelType
                it.channelIdentifier = channelIdentifier
                it.lifecycle = lifecycle
                it.createdAt = at
                it.createdBy = by
            }
    }
}

package com.paycore.payment.entitlement.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/** entitlement_mid_owner 행(append-only — MID 소속 불변). */
@Entity
@Table(name = "entitlement_mid_owner")
class JpaMidOwnerEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantAccountId == (other as JpaMidOwnerEntity).merchantAccountId)

    override fun hashCode(): Int = merchantAccountId.hashCode()

    override fun toString(): String = "JpaMidOwnerEntity(mid=$merchantAccountId, merchantId=$merchantId)"

    companion object {
        fun newRow(mid: String, merchantId: String, at: Instant) = JpaMidOwnerEntity().also {
            it.merchantAccountId = mid
            it.merchantId = merchantId
            it.recordedAt = at
        }
    }
}

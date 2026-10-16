package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

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

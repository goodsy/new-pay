package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** currency_enablement 행. 종료는 변경 감지 + `@Version`(effective_to·ended_*만 갱신 가능). */
@Entity
@Table(name = "currency_enablement")
class JpaCurrencyEnablementEntity protected constructor() {
    @field:Id
    @field:Column(name = "enablement_id", nullable = false, updatable = false, length = 64)
    lateinit var enablementId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currencyCode: String
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to")
    var effectiveTo: Instant? = null

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "ended_at")
    var endedAt: Instant? = null

    @field:Column(name = "ended_by", length = 64)
    var endedBy: String? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && enablementId == (other as JpaCurrencyEnablementEntity).enablementId)

    override fun hashCode(): Int = enablementId.hashCode()

    override fun toString(): String = "JpaCurrencyEnablementEntity(enablementId=$enablementId, currency=$currencyCode)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            accountId: String,
            currency: String,
            from: Instant,
            to: Instant?,
            evidenceRef: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaCurrencyEnablementEntity().also {
            it.enablementId = id
            it.merchantAccountId = accountId
            it.currencyCode = currency
            it.effectiveFrom = from
            it.effectiveTo = to
            it.evidenceRef = evidenceRef
            it.createdAt = at
            it.createdBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

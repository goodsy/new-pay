package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/** acquisition_exclusion 행(매입 제외 기록 — append-only, 승인 하나에 한 행). */
@Entity
@Immutable
@Table(name = "acquisition_exclusion")
class JpaAcquisitionExclusionEntity protected constructor() {
    @field:Id
    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 8)
    lateinit var environment: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 40)
    lateinit var reason: String
        protected set

    @field:Column(name = "approved_amount_minor", nullable = false, updatable = false)
    var approvedAmountMinor: Long = 0
        protected set

    @field:Column(name = "netted_amount_minor", nullable = false, updatable = false)
    var nettedAmountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "business_date", nullable = false, updatable = false)
    lateinit var businessDate: LocalDate
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionExclusionEntity(transactionId=$transactionId, reason=$reason)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            transactionId: String, acquirerCode: String, environment: String, reason: String, approvedAmountMinor: Long, nettedAmountMinor: Long,
            currency: String, businessDate: LocalDate, decidedAt: Instant,
        ) = JpaAcquisitionExclusionEntity().also {
            it.transactionId = transactionId
            it.acquirerCode = acquirerCode
            it.environment = environment
            it.reason = reason
            it.approvedAmountMinor = approvedAmountMinor
            it.nettedAmountMinor = nettedAmountMinor
            it.currency = currency
            it.businessDate = businessDate
            it.decidedAt = decidedAt
        }
    }
}

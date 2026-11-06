package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/** acquisition_cancel_not_required 행("취소 매입 불요" — D-ACQ-009, 전송 후 취소 하나에 한 행, append-only, `@Immutable`). */
@Entity
@Immutable
@Table(name = "acquisition_cancel_not_required")
class JpaAcquisitionCancelNotRequiredEntity protected constructor() {
    @field:Id
    @field:Column(name = "cancellation_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationTransactionId: String
        protected set

    @field:Column(name = "original_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var originalTransactionId: String
        protected set

    @field:Column(name = "original_file_id", nullable = false, updatable = false, length = 64)
    lateinit var originalFileId: String
        protected set

    @field:Column(name = "original_seq", nullable = false, updatable = false)
    var originalSeq: Int = 0
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
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

    override fun toString(): String = "JpaAcquisitionCancelNotRequiredEntity(cancellation=$cancellationTransactionId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            cancellationTransactionId: String, originalTransactionId: String, originalFileId: String, originalSeq: Int, amountMinor: Long, currency: String,
            businessDate: LocalDate, decidedAt: Instant,
        ) = JpaAcquisitionCancelNotRequiredEntity().also {
            it.cancellationTransactionId = cancellationTransactionId
            it.originalTransactionId = originalTransactionId
            it.originalFileId = originalFileId
            it.originalSeq = originalSeq
            it.amountMinor = amountMinor
            it.currency = currency
            it.businessDate = businessDate
            it.decidedAt = decidedAt
        }
    }
}

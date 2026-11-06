package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** acquisition_netting 행(상계 기록 — append-only, 취소 하나에 한 행). */
@Entity
@Immutable
@Table(name = "acquisition_netting")
class JpaAcquisitionNettingEntity protected constructor() {
    @field:Id
    @field:Column(name = "cancellation_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationTransactionId: String
        protected set

    @field:Column(name = "original_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var originalTransactionId: String
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "file_id", updatable = false, length = 64)
    var fileId: String? = null
        protected set

    @field:Column(name = "seq", updatable = false)
    var seq: Int? = null
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionNettingEntity(cancellation=$cancellationTransactionId, fileId=$fileId, seq=$seq)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            cancellationTransactionId: String, originalTransactionId: String, amountMinor: Long, currency: String, fileId: String?, seq: Int?, decidedAt: Instant,
        ) = JpaAcquisitionNettingEntity().also {
            it.cancellationTransactionId = cancellationTransactionId
            it.originalTransactionId = originalTransactionId
            it.amountMinor = amountMinor
            it.currency = currency
            it.fileId = fileId
            it.seq = seq
            it.decidedAt = decidedAt
        }
    }
}

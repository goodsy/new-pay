package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/**
 * acquisition_reacquisition_not_required 행("재매입 불요" 종결 — D-ACQA-004, append-only, `@Immutable`). PK(거래, 종류) = 종결은 한 번. 가리키는 승인 행(승인 ID, 파일, 순번)이
 * FK다. 연관 매핑 없이 값만 둔다.
 */
@Entity
@Immutable
@IdClass(JpaAcquisitionReacquisitionNotRequiredEntity.Key::class)
@Table(name = "acquisition_reacquisition_not_required")
class JpaAcquisitionReacquisitionNotRequiredEntity protected constructor() {
    @field:Id
    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Id
    @field:Column(name = "record_type", nullable = false, updatable = false, length = 8)
    lateinit var recordType: String
        protected set

    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 64)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "approved_amount_minor", nullable = false, updatable = false)
    var approvedAmountMinor: Long = 0
        protected set

    @field:Column(name = "cancelled_amount_minor", nullable = false, updatable = false)
    var cancelledAmountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionReacquisitionNotRequiredEntity(transactionId=$transactionId, recordType=$recordType)"

    data class Key(var transactionId: String = "", var recordType: String = "") : Serializable

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            transactionId: String, recordType: String, approvalId: String, fileId: String, seq: Int, approvedAmountMinor: Long, cancelledAmountMinor: Long,
            currency: String, decidedAt: Instant,
        ) = JpaAcquisitionReacquisitionNotRequiredEntity().also {
            it.transactionId = transactionId
            it.recordType = recordType
            it.approvalId = approvalId
            it.fileId = fileId
            it.seq = seq
            it.approvedAmountMinor = approvedAmountMinor
            it.cancelledAmountMinor = cancelledAmountMinor
            it.currency = currency
            it.decidedAt = decidedAt
        }
    }
}

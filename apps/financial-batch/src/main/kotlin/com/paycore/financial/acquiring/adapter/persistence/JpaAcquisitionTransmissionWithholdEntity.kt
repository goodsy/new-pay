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
 * acquisition_transmission_withhold 행(ACQ-ADMIN-001 B2 — §15 L-9, 재매입 파일 전송 보류 근거, append-only, `@Immutable`). PK(파일, 순번) = 보류한 파일의 레코드마다 한 번.
 * 그 거래·종류의 매입 확정 표지가 가리키던 다른 세대 레코드(accepted_file_id, accepted_seq)가 FK다. 연관 매핑 없이 값만 둔다(Store는 persist만 — merge 금지).
 */
@Entity
@Immutable
@IdClass(JpaAcquisitionTransmissionWithholdEntity.Key::class)
@Table(name = "acquisition_transmission_withhold")
class JpaAcquisitionTransmissionWithholdEntity protected constructor() {
    @field:Id
    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Id
    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "record_type", nullable = false, updatable = false, length = 8)
    lateinit var recordType: String
        protected set

    @field:Column(name = "accepted_file_id", nullable = false, updatable = false, length = 64)
    lateinit var acceptedFileId: String
        protected set

    @field:Column(name = "accepted_seq", nullable = false, updatable = false)
    var acceptedSeq: Int = 0
        protected set

    @field:Column(name = "accepted_generation", nullable = false, updatable = false)
    var acceptedGeneration: Int = 0
        protected set

    @field:Column(name = "prior_state", nullable = false, updatable = false, length = 24)
    lateinit var priorState: String
        protected set

    @field:Column(name = "withheld_at", nullable = false, updatable = false)
    lateinit var withheldAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionTransmissionWithholdEntity(fileId=$fileId, seq=$seq)"

    data class Key(var fileId: String = "", var seq: Int = 0) : Serializable

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            fileId: String, seq: Int, transactionId: String, recordType: String, acceptedFileId: String, acceptedSeq: Int, acceptedGeneration: Int,
            priorState: String, withheldAt: Instant,
        ) = JpaAcquisitionTransmissionWithholdEntity().also {
            it.fileId = fileId
            it.seq = seq
            it.transactionId = transactionId
            it.recordType = recordType
            it.acceptedFileId = acceptedFileId
            it.acceptedSeq = acceptedSeq
            it.acceptedGeneration = acceptedGeneration
            it.priorState = priorState
            it.withheldAt = withheldAt
        }
    }
}

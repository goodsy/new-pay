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
 * acquisition_accepted_record 행(매입 확정 표지 — D-ACQ-014 "거래·종류당 세대를 넘는 ACCEPTED는 하나", append-only, `@Immutable`). PK(거래, 종류)가 DB 방어이고
 * 앱 규칙(`RecordResultRule.apply`의 다른 세대 ACCEPTED → CONFLICT)이 먼저 거른다. 동시 반영 경합은 PK 위반 → UniqueConflict → 새 TX 재시도가 CONFLICT로 수렴한다.
 */
@Entity
@Immutable
@IdClass(JpaAcquisitionAcceptedRecordEntity.Key::class)
@Table(name = "acquisition_accepted_record")
class JpaAcquisitionAcceptedRecordEntity protected constructor() {
    @field:Id
    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Id
    @field:Column(name = "record_type", nullable = false, updatable = false, length = 8)
    lateinit var recordType: String
        protected set

    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "entry_no", nullable = false, updatable = false)
    var entryNo: Int = 0
        protected set

    @field:Column(name = "generation", nullable = false, updatable = false)
    var generation: Int = 0
        protected set

    @field:Column(name = "accepted_at", nullable = false, updatable = false)
    lateinit var acceptedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionAcceptedRecordEntity(transactionId=$transactionId, recordType=$recordType, fileId=$fileId, seq=$seq)"

    data class Key(var transactionId: String = "", var recordType: String = "") : Serializable

    companion object {
        @Suppress("LongParameterList")
        fun newRow(transactionId: String, recordType: String, fileId: String, seq: Int, entryNo: Int, generation: Int, acceptedAt: Instant) =
            JpaAcquisitionAcceptedRecordEntity().also {
                it.transactionId = transactionId
                it.recordType = recordType
                it.fileId = fileId
                it.seq = seq
                it.entryNo = entryNo
                it.generation = generation
                it.acceptedAt = acceptedAt
            }
    }
}

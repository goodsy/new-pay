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
 * acquisition_record_result 행(레코드 결과 — append-only 결정 로그, `@Immutable`·updatable=false). 복합 키 (file_id, seq, entry_no)는 `@IdClass` — 같은 레코드에 같은 행 번호를
 * 두 번 쓰면 PK 위반(동시 반영의 DB 방어 — 응답 TX는 파일 행을 잠근 뒤 쓴다). 레코드·응답과 연관 매핑 없이 값만 둔다(cascade·지연 로딩 경로 없음).
 */
@Entity
@Immutable
@IdClass(JpaAcquisitionRecordResultEntity.Key::class)
@Table(name = "acquisition_record_result")
class JpaAcquisitionRecordResultEntity protected constructor() {
    @field:Id
    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Id
    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Id
    @field:Column(name = "entry_no", nullable = false, updatable = false)
    var entryNo: Int = 0
        protected set

    @field:Column(name = "result", nullable = false, updatable = false, length = 16)
    lateinit var result: String
        protected set

    @field:Column(name = "observed_result", updatable = false, length = 16)
    var observedResult: String? = null
        protected set

    @field:Column(name = "reason_code", updatable = false, length = 32)
    var reasonCode: String? = null
        protected set

    @field:Column(name = "source", nullable = false, updatable = false, length = 16)
    lateinit var source: String
        protected set

    @field:Column(name = "response_id", updatable = false)
    var responseId: Long? = null
        protected set

    @field:Column(name = "recognized_at", nullable = false, updatable = false)
    lateinit var recognizedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionRecordResultEntity(fileId=$fileId, seq=$seq, entryNo=$entryNo, result=$result)"

    data class Key(var fileId: String = "", var seq: Int = 0, var entryNo: Int = 0) : Serializable

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            fileId: String, seq: Int, entryNo: Int, result: String, observedResult: String?, reasonCode: String?, source: String, responseId: Long?, recognizedAt: Instant,
        ) = JpaAcquisitionRecordResultEntity().also {
            it.fileId = fileId
            it.seq = seq
            it.entryNo = entryNo
            it.result = result
            it.observedResult = observedResult
            it.reasonCode = reasonCode
            it.source = source
            it.responseId = responseId
            it.recognizedAt = recognizedAt
        }
    }
}

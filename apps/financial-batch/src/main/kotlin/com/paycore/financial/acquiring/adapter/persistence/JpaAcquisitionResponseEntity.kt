package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * acquisition_response 행(기관 ACK·결과 파일 처리 기록 — append-only, `@Immutable`·updatable=false, runtime 권한 SELECT·INSERT).
 *
 * JPA 선택(이번 코드): PK는 IDENTITY — persist가 곧바로 INSERT되어 같은 TX의 레코드 결과 행이 그 id를 FK로 쓴다(flush ≠ commit). 받은 바이트(MEDIUMBLOB)를 같은 행에 두지만
 * 이 Entity를 다시 읽는 운영 경로는 없다(처리 여부는 native 스칼라로 (이름, hash, 크기)만 읽는다 — BLOB를 읽지 않는다). UNIQUE(매입사, 이름, hash) 위반(동시 처리)은
 * Port 안에서 UniqueConflict로 분류되어 Use Case가 새 TX로 다시 확인한다.
 */
@Entity
@Immutable
@Table(name = "acquisition_response")
class JpaAcquisitionResponseEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 8)
    lateinit var environment: String
        protected set

    @field:Column(name = "file_name", nullable = false, updatable = false, length = 128)
    lateinit var fileName: String
        protected set

    @field:Column(name = "content_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var contentSha256: String
        protected set

    @field:Column(name = "content_bytes", nullable = false, updatable = false)
    var contentBytes: Int = 0
        protected set

    @field:Column(name = "content", nullable = false, updatable = false, columnDefinition = "mediumblob")
    lateinit var content: ByteArray
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 8)
    lateinit var kind: String
        protected set

    @field:Column(name = "file_id", updatable = false, length = 64)
    var fileId: String? = null
        protected set

    @field:Column(name = "sequence_no", nullable = false, updatable = false)
    var sequenceNo: Int = 0
        protected set

    @field:Column(name = "disposition", nullable = false, updatable = false, length = 16)
    lateinit var disposition: String
        protected set

    @field:Column(name = "quarantine_reason", updatable = false, length = 24)
    var quarantineReason: String? = null
        protected set

    @field:Column(name = "ack_outcome", updatable = false, length = 24)
    var ackOutcome: String? = null
        protected set

    @field:Column(name = "ack_reason_code", updatable = false, length = 32)
    var ackReasonCode: String? = null
        protected set

    @field:Column(name = "institution_at", updatable = false)
    var institutionAt: Instant? = null
        protected set

    @field:Column(name = "evidence", updatable = false, length = 24)
    var evidence: String? = null
        protected set

    @field:Column(name = "observed_at", nullable = false, updatable = false)
    lateinit var observedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionResponseEntity(id=$id, fileName=$fileName, disposition=$disposition)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            acquirerCode: String, environment: String, fileName: String, contentSha256: String, content: ByteArray, kind: String, fileId: String?, sequenceNo: Int,
            disposition: String, quarantineReason: String?, ackOutcome: String?, ackReasonCode: String?, institutionAt: Instant?, evidence: String?, observedAt: Instant,
        ) = JpaAcquisitionResponseEntity().also {
            it.acquirerCode = acquirerCode
            it.environment = environment
            it.fileName = fileName
            it.contentSha256 = contentSha256
            it.contentBytes = content.size
            it.content = content.copyOf()
            it.kind = kind
            it.fileId = fileId
            it.sequenceNo = sequenceNo
            it.disposition = disposition
            it.quarantineReason = quarantineReason
            it.ackOutcome = ackOutcome
            it.ackReasonCode = ackReasonCode
            it.institutionAt = institutionAt
            it.evidence = evidence
            it.observedAt = observedAt
        }
    }
}

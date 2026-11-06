package com.paycore.simulator.acquirer.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * acquirer_received_file 행(가상 매입사 수신 원장 — append-only). JPA 선택(이번 코드): **`@Immutable` + updatable=false** — 기관 수신 사실은 바뀌지 않으므로
 * Hibernate가 변경 감지 UPDATE를 만들지 않게 한다(DB 권한도 SELECT·INSERT만 — V12, 이중 방어). PK는 DB AUTO_INCREMENT(IDENTITY — persist 즉시 INSERT라
 * 같은 TX에서 판정·결과 줄·작업이 그 id를 FK로 쓴다). (매입사, 파일 ID, 내용 hash) UNIQUE가 같은 내용의 동시 수신을 하나로 만든다.
 */
@Entity
@Immutable
@Table(name = "acquirer_received_file")
class JpaAcquirerReceivedFileEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "receipt_id", nullable = false, updatable = false)
    var receiptId: Long? = null
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Column(name = "receipt_ordinal", nullable = false, updatable = false)
    var receiptOrdinal: Int = 0
        protected set

    @field:Column(name = "content_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var contentSha256: String
        protected set

    @field:Column(name = "content_bytes", nullable = false, updatable = false)
    var contentBytes: Long = 0
        protected set

    @field:Column(name = "record_count", updatable = false)
    var recordCount: Int? = null
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 20)
    lateinit var outcome: String
        protected set

    @field:Column(name = "reason_code", updatable = false, length = 32)
    var reasonCode: String? = null
        protected set

    @field:Column(name = "received_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    lateinit var receivedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is JpaAcquirerReceivedFileEntity && receiptId != null && receiptId == other.receiptId)

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "JpaAcquirerReceivedFileEntity(receiptId=$receiptId, fileId=$fileId, ordinal=$receiptOrdinal)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            acquirerCode: String, fileId: String, ordinal: Int, contentSha256: String, contentBytes: Long, recordCount: Int?, outcome: String, reasonCode: String?,
            receivedAt: Instant,
        ) = JpaAcquirerReceivedFileEntity().apply {
            this.acquirerCode = acquirerCode
            this.fileId = fileId
            this.receiptOrdinal = ordinal
            this.contentSha256 = contentSha256
            this.contentBytes = contentBytes
            this.recordCount = recordCount
            this.outcome = outcome
            this.reasonCode = reasonCode
            this.receivedAt = receivedAt
        }
    }
}

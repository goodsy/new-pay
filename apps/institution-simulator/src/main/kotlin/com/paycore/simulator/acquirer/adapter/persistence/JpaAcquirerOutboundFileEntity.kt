package com.paycore.simulator.acquirer.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/** acquirer_outbound_file 행(outbox 게시 기록 — append-only, `@Immutable`). 작업 하나당 하나(UNIQUE work_item_id), 이름 하나당 하나(PK). */
@Entity
@Immutable
@IdClass(AcquirerOutboundFileKey::class)
@Table(name = "acquirer_outbound_file")
class JpaAcquirerOutboundFileEntity protected constructor() {
    @field:Id
    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Id
    @field:Column(name = "file_name", nullable = false, updatable = false, length = 128)
    lateinit var fileName: String
        protected set

    @field:Column(name = "work_item_id", nullable = false, updatable = false)
    var workItemId: Long = 0
        protected set

    @field:Column(name = "content_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var contentSha256: String
        protected set

    @field:Column(name = "content_bytes", nullable = false, updatable = false)
    var contentBytes: Int = 0
        protected set

    @field:Column(name = "published_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    lateinit var publishedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other is JpaAcquirerOutboundFileEntity && acquirerCode == other.acquirerCode && fileName == other.fileName)

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "JpaAcquirerOutboundFileEntity(acquirerCode=$acquirerCode, fileName=$fileName)"

    companion object {
        fun newRow(acquirerCode: String, fileName: String, workItemId: Long, contentSha256: String, contentBytes: Int, publishedAt: Instant) =
            JpaAcquirerOutboundFileEntity().apply {
                this.acquirerCode = acquirerCode
                this.fileName = fileName
                this.workItemId = workItemId
                this.contentSha256 = contentSha256
                this.contentBytes = contentBytes
                this.publishedAt = publishedAt
            }
    }
}

/** [JpaAcquirerOutboundFileEntity] 복합 키. */
class AcquirerOutboundFileKey() : Serializable {
    var acquirerCode: String = ""
    var fileName: String = ""

    override fun equals(other: Any?): Boolean = other is AcquirerOutboundFileKey && acquirerCode == other.acquirerCode && fileName == other.fileName

    override fun hashCode(): Int = 31 * acquirerCode.hashCode() + fileName.hashCode()
}

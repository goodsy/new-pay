package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/**
 * acquisition_file_content 행(봉인 바이트 — append-only). 파일 행과 표를 나눠 연관 없이 file_id로만 읽는다: `@Lob @Basic(fetch = LAZY)`는 Hibernate bytecode
 * enhancement 없이는 지켜지지 않아, 한 표에 두면 파일 상태를 읽을 때마다 BLOB를 함께 읽게 된다.
 */
@Entity
@Immutable
@Table(name = "acquisition_file_content")
class JpaAcquisitionFileContentEntity protected constructor() {
    @field:Id
    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Column(name = "content", nullable = false, updatable = false, columnDefinition = "mediumblob")
    lateinit var content: ByteArray
        protected set

    @field:Column(name = "content_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var contentSha256: String
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionFileContentEntity(fileId=$fileId, bytes=${content.size})"

    companion object {
        fun newRow(fileId: String, content: ByteArray, contentSha256: String) = JpaAcquisitionFileContentEntity().also {
            it.fileId = fileId
            it.content = content.copyOf()
            it.contentSha256 = contentSha256
        }
    }
}

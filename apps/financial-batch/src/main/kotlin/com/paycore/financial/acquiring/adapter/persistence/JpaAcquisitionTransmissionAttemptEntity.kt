package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/** acquisition_transmission_attempt 행(append-only 전송 시도 — `@Immutable`·updatable=false, runtime 권한 SELECT·INSERT). 복합 키 = `@IdClass`. */
@Entity
@Immutable
@IdClass(JpaAcquisitionTransmissionAttemptEntity.Key::class)
@Table(name = "acquisition_transmission_attempt")
class JpaAcquisitionTransmissionAttemptEntity protected constructor() {
    @field:Id
    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Id
    @field:Column(name = "attempt_no", nullable = false, updatable = false)
    var attemptNo: Int = 0
        protected set

    @field:Column(name = "resume_state", nullable = false, updatable = false, length = 24)
    lateinit var resumeState: String
        protected set

    @field:Column(name = "content_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var contentSha256: String
        protected set

    @field:Column(name = "claimed_at", nullable = false, updatable = false)
    lateinit var claimedAt: Instant
        protected set

    @field:Column(name = "lease_until", nullable = false, updatable = false)
    lateinit var leaseUntil: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionTransmissionAttemptEntity(fileId=$fileId, attemptNo=$attemptNo)"

    /** 복합 키 값 객체(값 equals — Hibernate가 1차 캐시 키로 쓴다). */
    data class Key(var fileId: String = "", var attemptNo: Int = 0) : Serializable

    companion object {
        fun newRow(fileId: String, attemptNo: Int, resumeState: String, contentSha256: String, claimedAt: Instant, leaseUntil: Instant) =
            JpaAcquisitionTransmissionAttemptEntity().also {
                it.fileId = fileId
                it.attemptNo = attemptNo
                it.resumeState = resumeState
                it.contentSha256 = contentSha256
                it.claimedAt = claimedAt
                it.leaseUntil = leaseUntil
            }
    }
}

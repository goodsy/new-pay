package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * cancel_attempt 행(CANCEL-001 명세 §8.1 — 취소당 하나). 기관 요청 namespace·준비 시각은 고정, dispatch 상태·원인·Route 프로파일 버전만 바뀐다(`@Version` — 전송 게이트와 회복 fencing이
 * 같은 행에서 경쟁). `institutionAccount`(기관 계약계정)는 내부 식별자 등급(D-MS2-005) — [toString]에 넣지 않는다.
 */
@Entity
@Table(name = "cancel_attempt")
class JpaCancelAttemptEntity protected constructor() {
    @field:Id
    @field:Column(name = "attempt_id", nullable = false, updatable = false, length = 64)
    lateinit var attemptId: String
        protected set

    @field:Column(name = "cancellation_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationId: String
        protected set

    @field:Column(name = "institution", nullable = false, updatable = false, length = 64)
    lateinit var institution: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "institution_account", nullable = false, updatable = false, length = 128)
    lateinit var institutionAccount: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 32)
    lateinit var operation: String
        protected set

    @field:Column(name = "request_id", nullable = false, updatable = false, length = 64)
    lateinit var requestId: String
        protected set

    @field:Column(name = "state", nullable = false, length = 32)
    lateinit var state: String

    @field:Column(name = "abandon_cause", length = 32)
    var abandonCause: String? = null

    @field:Column(name = "unknown_cause", length = 32)
    var unknownCause: String? = null

    /** 전송 게이트 통과 때 고정한 Route 프로파일 버전(D-CANCEL1-004). PREPARED·전송 전 포기는 NULL. */
    @field:Column(name = "route_profile_version", length = 64)
    var routeProfileVersion: String? = null

    @field:Column(name = "prepared_at", nullable = false, updatable = false)
    lateinit var preparedAt: Instant
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return attemptId == (other as JpaCancelAttemptEntity).attemptId
    }

    override fun hashCode(): Int = attemptId.hashCode()

    override fun toString(): String = "JpaCancelAttemptEntity(attemptId=$attemptId, state=$state, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            attemptId: String, cancellationId: String, institution: String, environment: String, institutionAccount: String, operation: String, requestId: String,
            state: String, abandonCause: String?, unknownCause: String?, routeProfileVersion: String?, preparedAt: Instant,
        ): JpaCancelAttemptEntity = JpaCancelAttemptEntity().also {
            it.attemptId = attemptId
            it.cancellationId = cancellationId
            it.institution = institution
            it.environment = environment
            it.institutionAccount = institutionAccount
            it.operation = operation
            it.requestId = requestId
            it.state = state
            it.abandonCause = abandonCause
            it.unknownCause = unknownCause
            it.routeProfileVersion = routeProfileVersion
            it.preparedAt = preparedAt
        }
    }
}

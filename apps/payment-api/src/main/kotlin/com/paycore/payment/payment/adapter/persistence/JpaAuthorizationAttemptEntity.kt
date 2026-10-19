package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * authorization_attempt 행(AUTH-001 명세 §7.1). 기관 요청 namespace·준비 시각은 고정, dispatch 상태·원인만 바뀐다. 상태 변경은 잠가 읽은 행의 변경 감지 +
 * `@Version`(`UPDATE … WHERE version = ?`)이다 — 전송 게이트의 PREPARED → DISPATCHING과 회복의 PREPARED → ABANDONED가 같은 행에서 경쟁한다(fencing, §7.5).
 * 생성 열 `in_flight_authorization_id`는 매핑하지 않는다(DB가 계산, UNIQUE로 동시 실행 Attempt 하나를 지킨다).
 * `institutionAccount`(기관 계약계정)는 내부 식별자 등급(D-MS2-005) — [toString]에 넣지 않는다.
 */
@Entity
@Table(name = "authorization_attempt")
class JpaAuthorizationAttemptEntity protected constructor() {
    @field:Id
    @field:Column(name = "attempt_id", nullable = false, updatable = false, length = 64)
    lateinit var attemptId: String
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
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

    /** 전송 게이트 통과 때 고정한 Route 프로파일 버전(V14, D-AUTH1-007). PREPARED·V14 이전 행은 NULL. */
    @field:Column(name = "route_profile_version", length = 64)
    var routeProfileVersion: String? = null

    @field:Column(name = "prepared_at", nullable = false, updatable = false)
    lateinit var preparedAt: Instant
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return attemptId == (other as JpaAuthorizationAttemptEntity).attemptId
    }

    override fun hashCode(): Int = attemptId.hashCode()

    override fun toString(): String = "JpaAuthorizationAttemptEntity(attemptId=$attemptId, state=$state, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            attemptId: String, authorizationId: String, institution: String, environment: String, institutionAccount: String, operation: String, requestId: String,
            state: String, abandonCause: String?, unknownCause: String?, preparedAt: Instant, seq: Int, routeProfileVersion: String? = null,
        ): JpaAuthorizationAttemptEntity = JpaAuthorizationAttemptEntity().also {
            it.routeProfileVersion = routeProfileVersion
            it.attemptId = attemptId
            it.authorizationId = authorizationId
            it.institution = institution
            it.environment = environment
            it.institutionAccount = institutionAccount
            it.operation = operation
            it.requestId = requestId
            it.state = state
            it.abandonCause = abandonCause
            it.unknownCause = unknownCause
            it.preparedAt = preparedAt
            it.seq = seq
        }
    }
}

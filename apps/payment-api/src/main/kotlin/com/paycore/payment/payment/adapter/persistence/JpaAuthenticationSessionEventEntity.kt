package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/** authentication_session_event 복합 키(authentication_session_id, seq). */
@Embeddable
class AuthenticationSessionEventKey protected constructor() : Serializable {
    @field:Column(name = "authentication_session_id", nullable = false, updatable = false, length = 64)
    lateinit var authenticationSessionId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other is AuthenticationSessionEventKey && authenticationSessionId == other.authenticationSessionId && seq == other.seq)

    override fun hashCode(): Int = 31 * authenticationSessionId.hashCode() + seq

    override fun toString(): String = "AuthenticationSessionEventKey($authenticationSessionId#$seq)"

    companion object {
        private const val serialVersionUID = 1L

        fun of(authenticationSessionId: String, seq: Int): AuthenticationSessionEventKey = AuthenticationSessionEventKey().also {
            it.authenticationSessionId = authenticationSessionId
            it.seq = seq
        }
    }
}

/**
 * 인증 세션 결정 로그 한 건(append-only). `@Immutable`: 변경 감지가 UPDATE를 만들지 않는다 — runtime 권한도 SELECT·INSERT뿐이라 이중으로 막힌다.
 * 생성 컬럼(repeat_key)은 매핑하지 않는다.
 */
@Entity
@Immutable
@Table(name = "authentication_session_event")
class JpaAuthenticationSessionEventEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: AuthenticationSessionEventKey
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 24)
    lateinit var kind: String
        protected set

    @field:Column(name = "detail_code", updatable = false, length = 32)
    var detailCode: String? = null
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    @field:Column(name = "correlation_id", nullable = false, updatable = false, length = 128)
    lateinit var correlationId: String
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return key == (other as JpaAuthenticationSessionEventEntity).key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaAuthenticationSessionEventEntity($key, kind=$kind)"

    companion object {
        fun newRow(sessionId: String, seq: Int, kind: String, detailCode: String?, occurredAt: Instant, correlationId: String) =
            JpaAuthenticationSessionEventEntity().also {
                it.key = AuthenticationSessionEventKey.of(sessionId, seq)
                it.kind = kind
                it.detailCode = detailCode
                it.occurredAt = occurredAt
                it.correlationId = correlationId
            }
    }
}

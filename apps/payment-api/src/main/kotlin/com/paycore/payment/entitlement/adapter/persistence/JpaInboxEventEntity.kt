package com.paycore.payment.entitlement.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/*
 * PAYMENT 권리 투영 JPA Entity(adapter 전용 — 순수 domain과 분리). append-only 표(inbox·snapshot·mid_owner)는 모든 컬럼 updatable=false이고
 * Store는 persist만 한다(runtime 계정에 UPDATE 권한 없음 — 1142). head는 잠가 읽은 Entity의 변경 감지 + `@Version`으로 갱신한다.
 * JSON 컬럼은 문자열(`@JdbcTypeCode(SqlTypes.JSON)`)로 매핑한다 — 읽을 때 domain이 구조·hash를 다시 확인한다.
 */

/** inbox_event 행(append-only). */
@Entity
@Table(name = "inbox_event")
class JpaInboxEventEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "consumer", nullable = false, updatable = false, length = 64)
    lateinit var consumer: String
        protected set

    @field:Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var eventId: String
        protected set

    @field:Column(name = "event_type", nullable = false, updatable = false, length = 64)
    lateinit var eventType: String
        protected set

    @field:Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = 0
        protected set

    @field:Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    lateinit var aggregateId: String
        protected set

    @field:Column(name = "aggregate_version", nullable = false, updatable = false)
    var aggregateVersion: Long = 0
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 24)
    lateinit var outcome: String
        protected set

    @field:Column(name = "head_revision_after", nullable = false, updatable = false)
    var headRevisionAfter: Long = 0
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "payload", nullable = false, updatable = false)
    lateinit var payload: String
        protected set

    @field:Column(name = "payload_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var payloadSha256: String
        protected set

    @field:Column(name = "received_at", nullable = false, updatable = false)
    lateinit var receivedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaInboxEventEntity(id=$id, eventId=$eventId, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            consumer: String,
            eventId: String,
            eventType: String,
            schemaVersion: Int,
            aggregateId: String,
            aggregateVersion: Long,
            outcome: String,
            headRevisionAfter: Long,
            payload: String,
            payloadSha256: String,
            receivedAt: Instant,
        ) = JpaInboxEventEntity().also {
            it.consumer = consumer
            it.eventId = eventId
            it.eventType = eventType
            it.schemaVersion = schemaVersion
            it.aggregateId = aggregateId
            it.aggregateVersion = aggregateVersion
            it.outcome = outcome
            it.headRevisionAfter = headRevisionAfter
            it.payload = payload
            it.payloadSha256 = payloadSha256
            it.receivedAt = receivedAt
        }
    }
}

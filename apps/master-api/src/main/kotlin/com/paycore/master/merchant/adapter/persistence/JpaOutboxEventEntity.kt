package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * outbox_event 행. 이벤트 내용 컬럼은 append(persist)만 하고 updatable=false다. Relay(MASTER-SNAPSHOT-001 §4.7)는 전달 상태 컬럼
 * (publish_status·published_at·claim·attempt·next_attempt_at·last_failure_code)만 잠가 읽은 Entity의 변경 감지로 갱신한다.
 */
@Entity
@Table(name = "outbox_event")
class JpaOutboxEventEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var eventId: String
        protected set

    @field:Column(name = "effect_key", nullable = false, updatable = false, length = 128)
    lateinit var effectKey: String
        protected set

    @field:Column(name = "event_type", nullable = false, updatable = false, length = 64)
    lateinit var eventType: String
        protected set

    @field:Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = 0
        protected set

    @field:Column(name = "event_category", nullable = false, updatable = false, length = 40)
    lateinit var eventCategory: String
        protected set

    @field:Column(name = "producer", nullable = false, updatable = false, length = 64)
    lateinit var producer: String
        protected set

    @field:Column(name = "aggregate_type", nullable = false, updatable = false, length = 48)
    lateinit var aggregateType: String
        protected set

    @field:Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    lateinit var aggregateId: String
        protected set

    @field:Column(name = "aggregate_version", nullable = false, updatable = false)
    var aggregateVersion: Long = 0
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    @field:Column(name = "correlation_id", nullable = false, updatable = false, length = 128)
    lateinit var correlationId: String
        protected set

    @field:Column(name = "causation_id", nullable = false, updatable = false, length = 64)
    lateinit var causationId: String
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "payload", nullable = false, updatable = false)
    lateinit var payload: String
        protected set

    @field:Column(name = "publish_status", nullable = false, length = 16)
    lateinit var publishStatus: String

    @field:Column(name = "published_at")
    var publishedAt: Instant? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "priority", nullable = false, updatable = false)
    var priority: Int = 0
        protected set

    @field:Column(name = "claim_owner", length = 64)
    var claimOwner: String? = null

    @field:Column(name = "claim_until")
    var claimUntil: Instant? = null

    @field:Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @field:Column(name = "next_attempt_at", nullable = false)
    lateinit var nextAttemptAt: Instant

    @field:Column(name = "last_failure_code", length = 48)
    var lastFailureCode: String? = null

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && eventId == (other as JpaOutboxEventEntity).eventId)

    override fun hashCode(): Int = eventId.hashCode()

    override fun toString(): String = "JpaOutboxEventEntity(eventId=$eventId, eventType=$eventType, status=$publishStatus)"

    companion object {
        const val PENDING = "PENDING"
        const val PUBLISHED = "PUBLISHED"
        const val QUARANTINED = "QUARANTINED"

        @Suppress("LongParameterList")
        fun pending(
            eventId: String,
            effectKey: String,
            eventType: String,
            schemaVersion: Int,
            eventCategory: String,
            producer: String,
            aggregateType: String,
            aggregateId: String,
            aggregateVersion: Long,
            occurredAt: Instant,
            correlationId: String,
            causationId: String,
            payload: String,
            priority: Int = 0,
        ): JpaOutboxEventEntity = JpaOutboxEventEntity().also {
            it.eventId = eventId
            it.effectKey = effectKey
            it.eventType = eventType
            it.schemaVersion = schemaVersion
            it.eventCategory = eventCategory
            it.producer = producer
            it.aggregateType = aggregateType
            it.aggregateId = aggregateId
            it.aggregateVersion = aggregateVersion
            it.occurredAt = occurredAt
            it.correlationId = correlationId
            it.causationId = causationId
            it.payload = payload
            it.publishStatus = PENDING
            it.createdAt = occurredAt
            it.priority = priority
            it.nextAttemptAt = occurredAt
        }
    }
}

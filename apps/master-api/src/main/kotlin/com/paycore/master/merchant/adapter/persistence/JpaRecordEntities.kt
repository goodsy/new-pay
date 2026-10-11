package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * admin_request 행. 최초 INSERT는 native `INSERT … ON DUPLICATE KEY UPDATE`(같은 키 동시 요청 직렬화), JPA는 잠가 읽고 결과를 반영한다
 * (변경 감지 + `@Version`). 범위 컬럼(operation, key)은 바뀌지 않는다. 멱등키 값은 toString에 넣지 않는다.
 */
@Entity
@Table(name = "admin_request")
class JpaAdminRequestEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long = 0
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 48)
    lateinit var operation: String
        protected set

    @field:Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    lateinit var idempotencyKey: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "request_fingerprint", nullable = false, updatable = false, length = 80)
    lateinit var requestFingerprint: String
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "result_kind", nullable = false, length = 16)
    lateinit var resultKind: String

    @field:Column(name = "result_code", length = 48)
    var resultCode: String? = null

    @field:Column(name = "result_reference", length = 64)
    var resultReference: String? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && id == (other as JpaAdminRequestEntity).id)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "JpaAdminRequestEntity(id=$id, operation=$operation, resultKind=$resultKind, version=$version)"
}

/** master_change_history 행(append-only 감사, persist만). detail은 JSON 문자열(adapter 직렬화). */
@Entity
@Table(name = "master_change_history")
class JpaMasterChangeHistoryEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 48)
    lateinit var operation: String
        protected set

    @field:Column(name = "aggregate_type", nullable = false, updatable = false, length = 48)
    lateinit var aggregateType: String
        protected set

    @field:Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    lateinit var aggregateId: String
        protected set

    @field:Column(name = "change_type", nullable = false, updatable = false, length = 48)
    lateinit var changeType: String
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "detail", nullable = false, updatable = false)
    lateinit var detail: String
        protected set

    /** 저장 전 id가 없으므로 동일성은 인스턴스 기준(이 Entity는 비교·컬렉션에 쓰지 않는다). */
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaMasterChangeHistoryEntity(id=$id, aggregateType=$aggregateType, changeType=$changeType)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            requestRecordId: String,
            operation: String,
            aggregateType: String,
            aggregateId: String,
            changeType: String,
            actorRef: String,
            occurredAt: Instant,
            detail: String,
        ) = JpaMasterChangeHistoryEntity().also {
            it.requestRecordId = requestRecordId
            it.operation = operation
            it.aggregateType = aggregateType
            it.aggregateId = aggregateId
            it.changeType = changeType
            it.actorRef = actorRef
            it.occurredAt = occurredAt
            it.detail = detail
        }
    }
}

/** outbox_event 행(append, persist만). payload는 JSON 컬럼(adapter가 직렬화한 문자열). Relay는 MASTER-SNAPSHOT-001. */
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
        protected set

    @field:Column(name = "published_at")
    var publishedAt: Instant? = null
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && eventId == (other as JpaOutboxEventEntity).eventId)

    override fun hashCode(): Int = eventId.hashCode()

    override fun toString(): String = "JpaOutboxEventEntity(eventId=$eventId, eventType=$eventType, status=$publishStatus)"

    companion object {
        const val PENDING = "PENDING"

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
        }
    }
}

package com.paycore.payment.payment.adapter.persistence

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
 * idempotency_request 행. 최초 INSERT는 adapter의 native `INSERT … ON DUPLICATE KEY`(같은 키 동시 요청 직렬화)이고, JPA는 잠가 읽고
 * 결과·갱신을 반영한다(변경 감지 + `@Version`). 범위 컬럼(environment, MID, operation, key)은 바뀌지 않는다.
 */
@Entity
@Table(name = "idempotency_request")
class JpaIdempotencyRequestEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long = 0
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 40)
    lateinit var operation: String
        protected set

    @field:Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
    lateinit var idempotencyKey: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, length = 64)
    lateinit var requestRecordId: String

    @field:Column(name = "request_fingerprint", nullable = false, length = 80)
    lateinit var requestFingerprint: String

    @field:Column(name = "result_kind", nullable = false, length = 16)
    lateinit var resultKind: String

    @field:Column(name = "result_code", length = 48)
    var resultCode: String? = null

    @field:Column(name = "result_payment_id", length = 64)
    var resultPaymentId: String? = null

    @field:Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @field:Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return id == (other as JpaIdempotencyRequestEntity).id
    }

    override fun hashCode(): Int = id.hashCode()

    /** 멱등키 값은 넣지 않는다(가맹점 입력, 로그 노출 최소화). */
    override fun toString(): String = "JpaIdempotencyRequestEntity(id=$id, resultKind=$resultKind, version=$version)"
}

/** outbox_event 행(append, JPA persist). payload는 JSON 컬럼(adapter가 직렬화한 문자열). */
@Entity
@Table(name = "outbox_event")
class JpaOutboxEventEntity protected constructor() {
    /** DB 부여 순서 키. 저장 전 null이므로 equals/hashCode는 eventId(삽입 전 확정)로 한다. 커밋 순서를 뜻하지 않는다. */
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

    @field:Column(name = "aggregate_type", nullable = false, updatable = false, length = 32)
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return eventId == (other as JpaOutboxEventEntity).eventId
    }

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
            createdAt: Instant,
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
            it.createdAt = createdAt
        }
    }
}

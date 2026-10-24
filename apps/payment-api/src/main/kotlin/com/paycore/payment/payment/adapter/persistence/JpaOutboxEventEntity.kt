package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/**
 * outbox_event 행(append, JPA persist). payload는 JSON 컬럼(adapter가 직렬화한 문자열).
 *
 * WEBHOOK-001 m-7: `@Immutable` — JPA가 이 행을 UPDATE하는 경로는 없다(변경 감지·merge 모두 무시). 가맹점 알림 표시 두 열(`merchant_notification_*`, V23)은 이 Entity에
 * 매핑하지 않으며 fan-out Store가 native PK UPDATE로만 바꾼다(D-WH-009 — DB 권한도 그 두 열만 열 단위 UPDATE, 나머지 열 1143). INSERT는 매핑한 열만 보내므로 새 열은 DEFAULT('PENDING')다.
 */
@Entity
@Immutable
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

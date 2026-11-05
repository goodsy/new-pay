package com.paycore.financial.paymentfact.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/*
 * FINANCIAL 결제 사실 JPA Entity(adapter 전용 — 순수 domain `PaymentFact`와 분리, Mapper가 옮긴다). append-only 표(inbox·authorization·cancellation·hold)는
 * `@Immutable`(Hibernate가 변경 감지·merge로 UPDATE를 만들지 않는다) + 모든 열 updatable=false이고 Store는 persist만 한다. 직렬화 행(stream)만 잠가 읽은 Entity의
 * 변경 감지 + `@Version`으로 갱신한다. equals/hashCode는 식별자 동일성(=== — data class 규칙을 쓰지 않는다: 영속 전 id가 없고 프록시와 비교될 수 있다).
 */

/** payment_fact_inbox 행(append-only). JSON 컬럼은 문자열로 매핑한다(MySQL이 정규화해 저장 — 원문 바이트 증거는 payloadSha256). */
@Entity
@Immutable
@Table(name = "payment_fact_inbox")
class JpaPaymentFactInboxEntity protected constructor() {
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

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 16)
    lateinit var outcome: String
        protected set

    @field:Column(name = "payment_id", updatable = false, length = 64)
    var paymentId: String? = null
        protected set

    @field:Column(name = "transaction_id", updatable = false, length = 64)
    var transactionId: String? = null
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "payload", nullable = false, updatable = false)
    lateinit var payload: String
        protected set

    @field:Column(name = "payload_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var payloadSha256: String
        protected set

    @field:Column(name = "signing_key_id", nullable = false, updatable = false, length = 32)
    lateinit var signingKeyId: String
        protected set

    @field:Column(name = "received_at", nullable = false, updatable = false)
    lateinit var receivedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaPaymentFactInboxEntity(id=$id, eventId=$eventId, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            eventId: String,
            effectKey: String,
            eventType: String,
            schemaVersion: Int,
            producer: String,
            aggregateType: String,
            aggregateId: String,
            aggregateVersion: Long,
            occurredAt: Instant,
            outcome: String,
            paymentId: String?,
            transactionId: String?,
            payload: String,
            payloadSha256: String,
            signingKeyId: String,
            receivedAt: Instant,
        ) = JpaPaymentFactInboxEntity().also {
            it.eventId = eventId
            it.effectKey = effectKey
            it.eventType = eventType
            it.schemaVersion = schemaVersion
            it.producer = producer
            it.aggregateType = aggregateType
            it.aggregateId = aggregateId
            it.aggregateVersion = aggregateVersion
            it.occurredAt = occurredAt
            it.outcome = outcome
            it.paymentId = paymentId
            it.transactionId = transactionId
            it.payload = payload
            it.payloadSha256 = payloadSha256
            it.signingKeyId = signingKeyId
            it.receivedAt = receivedAt
        }
    }
}

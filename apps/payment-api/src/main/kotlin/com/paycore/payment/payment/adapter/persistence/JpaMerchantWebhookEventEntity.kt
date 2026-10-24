package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * merchant_webhook_event 행(WEBHOOK-001 V21 — insert-only). `@Immutable` + runtime 권한 SELECT·INSERT(1142) 두 겹: 만든 뒤 본문 바이트·sequence가 바뀌는 경로가 없다(WH-08).
 * [body]는 보낸 바이트 그대로(MEDIUMBLOB). toString에 본문을 넣지 않는다.
 */
@Entity
@Immutable
@Table(name = "merchant_webhook_event")
class JpaMerchantWebhookEventEntity protected constructor() {
    @field:Id
    @field:Column(name = "webhook_event_id", nullable = false, updatable = false, columnDefinition = "char(30)")
    lateinit var webhookEventId: String
        protected set

    @field:Column(name = "source_event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var sourceEventId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "payment_sequence", nullable = false, updatable = false)
    var paymentSequence: Int = 0
        protected set

    @field:Column(name = "event_type", nullable = false, updatable = false, length = 32)
    lateinit var eventType: String
        protected set

    @field:Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = 0
        protected set

    @field:Column(name = "body", nullable = false, updatable = false, columnDefinition = "mediumblob")
    lateinit var body: ByteArray
        protected set

    @field:Column(name = "body_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var bodySha256: String
        protected set

    @field:Column(name = "delivery_plan", nullable = false, updatable = false, length = 16)
    lateinit var deliveryPlan: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return webhookEventId == (other as JpaMerchantWebhookEventEntity).webhookEventId
    }

    override fun hashCode(): Int = webhookEventId.hashCode()

    override fun toString(): String = "JpaMerchantWebhookEventEntity(webhookEventId=$webhookEventId, eventType=$eventType, sequence=$paymentSequence)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            webhookEventId: String, sourceEventId: String, environment: String, merchantAccountId: String, paymentId: String, paymentSequence: Int, eventType: String,
            schemaVersion: Int, body: ByteArray, bodySha256: String, deliveryPlan: String, createdAt: Instant,
        ) = JpaMerchantWebhookEventEntity().also {
            it.webhookEventId = webhookEventId
            it.sourceEventId = sourceEventId
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.paymentId = paymentId
            it.paymentSequence = paymentSequence
            it.eventType = eventType
            it.schemaVersion = schemaVersion
            it.body = body.copyOf()
            it.bodySha256 = bodySha256
            it.deliveryPlan = deliveryPlan
            it.createdAt = createdAt
        }
    }
}

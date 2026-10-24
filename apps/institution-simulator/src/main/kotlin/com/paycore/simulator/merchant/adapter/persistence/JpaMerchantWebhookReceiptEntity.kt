package com.paycore.simulator.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * merchant_webhook_receipt 행(append-only — `@Immutable`: Hibernate가 변경 감지 UPDATE를 만들지 않는다. DB 권한도 SELECT·INSERT만 — V9). PK는 DB AUTO_INCREMENT
 * (수신 순서 — 업무 식별자가 아니라 조회 정렬용). (수신기, first_acceptance_event_id) UNIQUE가 같은 이벤트의 동시 첫 수신을 하나로 만든다.
 */
@Entity
@Immutable
@Table(name = "merchant_webhook_receipt")
class JpaMerchantWebhookReceiptEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "receipt_id", nullable = false, updatable = false)
    var receiptId: Long? = null
        protected set

    @field:Column(name = "receiver_id", nullable = false, updatable = false, length = 64)
    lateinit var receiverId: String
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 24)
    lateinit var outcome: String
        protected set

    @field:Column(name = "event_id", updatable = false, length = 64)
    var eventId: String? = null
        protected set

    @field:Column(name = "event_type", updatable = false, length = 40)
    var eventType: String? = null
        protected set

    @field:Column(name = "event_sequence", updatable = false)
    var eventSequence: Long? = null
        protected set

    @field:Column(name = "environment", updatable = false, length = 8)
    var environment: String? = null
        protected set

    @field:Column(name = "key_id", updatable = false, length = 40)
    var keyId: String? = null
        protected set

    @field:Column(name = "body_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var bodySha256: String
        protected set

    @field:Column(name = "response_status", nullable = false, updatable = false, columnDefinition = "smallint")
    var responseStatus: Int = 0
        protected set

    @field:Column(name = "first_acceptance_event_id", updatable = false, length = 64)
    var firstAcceptanceEventId: String? = null
        protected set

    @field:Column(name = "received_at", nullable = false, updatable = false, columnDefinition = "datetime(3)")
    lateinit var receivedAt: Instant
        protected set

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            receiverId: String, outcome: String, eventId: String?, eventType: String?, eventSequence: Long?, environment: String?, keyId: String?,
            bodySha256: String, responseStatus: Int, firstAcceptanceEventId: String?, receivedAt: Instant,
        ): JpaMerchantWebhookReceiptEntity = JpaMerchantWebhookReceiptEntity().apply {
            this.receiverId = receiverId
            this.outcome = outcome
            this.eventId = eventId
            this.eventType = eventType
            this.eventSequence = eventSequence
            this.environment = environment
            this.keyId = keyId
            this.bodySha256 = bodySha256
            this.responseStatus = responseStatus
            this.firstAcceptanceEventId = firstAcceptanceEventId
            this.receivedAt = receivedAt
        }
    }
}

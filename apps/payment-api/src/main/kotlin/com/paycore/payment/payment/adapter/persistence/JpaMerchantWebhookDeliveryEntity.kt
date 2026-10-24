package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * merchant_webhook_delivery 행(WEBHOOK-001 V21). 행 모양만 갖고 상태 규칙은 domain `MerchantWebhookDelivery`가 지킨다(Mapper가 reconstitute로 되돌린다).
 * 식별·이벤트·환경·MID는 바꾸지 않는다(`updatable = false`). claim 열은 delivery worker bookkeeping(S3). `@Version`은 잠금 밖 낡은 객체 저장을 막는 두 번째 방어다.
 */
@Entity
@Table(name = "merchant_webhook_delivery")
class JpaMerchantWebhookDeliveryEntity protected constructor() {
    @field:Id
    @field:Column(name = "delivery_id", nullable = false, updatable = false, columnDefinition = "char(30)")
    lateinit var deliveryId: String
        protected set

    @field:Column(name = "webhook_event_id", nullable = false, updatable = false, columnDefinition = "char(30)")
    lateinit var webhookEventId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @field:Column(name = "next_attempt_at")
    var nextAttemptAt: Instant? = null

    @field:Column(name = "first_attempt_at")
    var firstAttemptAt: Instant? = null

    @field:Column(name = "last_failure_code", length = 48)
    var lastFailureCode: String? = null

    @field:Column(name = "delivered_at")
    var deliveredAt: Instant? = null

    @field:Column(name = "gave_up_at")
    var gaveUpAt: Instant? = null

    @field:Column(name = "claim_owner", length = 64)
    var claimOwner: String? = null

    @field:Column(name = "claim_until")
    var claimUntil: Instant? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return deliveryId == (other as JpaMerchantWebhookDeliveryEntity).deliveryId
    }

    override fun hashCode(): Int = deliveryId.hashCode()

    override fun toString(): String = "JpaMerchantWebhookDeliveryEntity(deliveryId=$deliveryId, status=$status, attemptCount=$attemptCount)"

    companion object {
        fun newRow(deliveryId: String, webhookEventId: String, environment: String, merchantAccountId: String, createdAt: Instant) =
            JpaMerchantWebhookDeliveryEntity().also {
                it.deliveryId = deliveryId
                it.webhookEventId = webhookEventId
                it.environment = environment
                it.merchantAccountId = merchantAccountId
                it.createdAt = createdAt
                it.updatedAt = createdAt
            }
    }
}

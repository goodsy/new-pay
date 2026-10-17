package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.PaymentCreationRecord
import com.paycore.payment.payment.application.required.PaymentExpiredV1Payload
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentStatus
import com.paycore.payment.payment.domain.TerminationEvidence
import java.time.Instant

/**
 * PaymentExpired v1 통합 이벤트(D-AUTHN-008, AUTHN-001 §7.5.7). REQUESTED → EXPIRED와 같은 PAYMENT 로컬 TX로 Outbox에 쓴다(모든 만료 경로 — [RequestedPaymentClosing]).
 * 금융 사실이 아니다(REQUESTED = 미전송, 기관 효과 없음). Payment당 하나(effect_key UNIQUE — 두 번째 만료 시도는 NOT_REQUESTED라 append하지 않는다).
 */
internal object PaymentExpiredEvents {
    const val EVENT_TYPE = "PaymentExpired"
    const val SCHEMA_VERSION = 1
    const val EVENT_CATEGORY = "INTEGRATION_NON_FINANCIAL"
    const val PRODUCER = "payment-api/payment"
    const val AGGREGATE_TYPE = "Payment"

    fun effectKey(payment: Payment): String = "$EVENT_TYPE:${payment.id.value}"

    fun of(
        payment: Payment,
        creation: PaymentCreationRecord,
        aggregateVersion: Long,
        expiredAt: Instant,
        eventId: EventId,
        correlationId: String,
        causationId: String,
    ): OutboxEvent {
        check(payment.status == PaymentStatus.EXPIRED) { "PaymentExpired needs an EXPIRED payment" }
        val window = checkNotNull(payment.checkoutWindow) { "an expired REQUESTED payment has a checkout window" }
        return OutboxEvent(
            eventId = eventId,
            eventType = EVENT_TYPE,
            schemaVersion = SCHEMA_VERSION,
            eventCategory = EVENT_CATEGORY,
            producer = PRODUCER,
            aggregateType = AGGREGATE_TYPE,
            aggregateId = payment.id.value,
            aggregateVersion = aggregateVersion,
            occurredAt = expiredAt,
            correlationId = correlationId,
            causationId = causationId,
            effectKey = effectKey(payment),
            payload = PaymentExpiredV1Payload(
                environment = payment.orderKey.environment,
                merchantAccountId = payment.orderKey.merchantAccountId.value,
                merchantSiteId = creation.attributes.merchantSiteId.value,
                merchantOrderId = payment.orderKey.merchantOrderId.value,
                paymentId = payment.id.value,
                amountMinor = creation.attributes.money.amountMinor,
                currency = creation.attributes.money.currency.value,
                paymentMethod = creation.attributes.paymentMethod.name,
                slotGeneration = payment.slotGeneration,
                lifecycle = payment.status.name,
                terminationEvidence = TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED.name,
                checkoutExpiresAt = window.expiresAt,
                checkoutValidityVersion = window.validityVersion,
                expiredAt = expiredAt,
            ),
        )
    }
}

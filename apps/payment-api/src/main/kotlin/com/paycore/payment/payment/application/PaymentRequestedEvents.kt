package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.required.OutboxEvent
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentRequestedV1Payload
import com.paycore.payment.payment.domain.Payment

/**
 * PaymentRequested v1 통합 이벤트(D-CPC-003, CP-CONTRACT-001 §10). Payment 생성(outcome CREATED)과 같은 TX로 Outbox에 쓴다.
 * 금융 사실이 아니며(eventCategory INTEGRATION_NON_FINANCIAL) FINANCIAL은 이것으로 정산 후보·채무를 만들지 않는다.
 */
internal object PaymentRequestedEvents {
    const val EVENT_TYPE = "PaymentRequested"
    const val SCHEMA_VERSION = 1
    const val EVENT_CATEGORY = "INTEGRATION_NON_FINANCIAL"
    const val PRODUCER = "payment-api/payment"
    const val AGGREGATE_TYPE = "Payment"

    /** Payment당 생성 이벤트 하나(Outbox UNIQUE effect_key). */
    fun effectKey(payment: Payment): String = "$EVENT_TYPE:${payment.id.value}"

    fun of(
        payment: Payment,
        attributes: PaymentCreationAttributes,
        aggregateVersion: Long,
        eventId: EventId,
        correlationId: String,
        causationId: String,
    ): OutboxEvent = OutboxEvent(
        eventId = eventId,
        eventType = EVENT_TYPE,
        schemaVersion = SCHEMA_VERSION,
        eventCategory = EVENT_CATEGORY,
        producer = PRODUCER,
        aggregateType = AGGREGATE_TYPE,
        aggregateId = payment.id.value,
        // 생성 시 영속 version(JPA @Version 최초값 0, 계약 §10이 CP-DB-001에 맡긴 값).
        aggregateVersion = aggregateVersion,
        occurredAt = attributes.createdAt,
        correlationId = correlationId,
        causationId = causationId,
        effectKey = effectKey(payment),
        payload = PaymentRequestedV1Payload(
            environment = payment.orderKey.environment,
            merchantAccountId = payment.orderKey.merchantAccountId.value,
            merchantSiteId = attributes.merchantSiteId.value,
            merchantOrderId = payment.orderKey.merchantOrderId.value,
            paymentId = payment.id.value,
            amountMinor = attributes.money.amountMinor,
            currency = attributes.money.currency.value,
            paymentMethod = attributes.paymentMethod.name,
            slotGeneration = payment.slotGeneration,
            orderIntentFingerprint = payment.intent.value,
            lifecycle = payment.status.name,
            requestedAt = attributes.createdAt,
        ),
    )
}

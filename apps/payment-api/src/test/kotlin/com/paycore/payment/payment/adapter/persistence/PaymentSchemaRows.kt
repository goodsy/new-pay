package com.paycore.payment.payment.adapter.persistence

/**
 * 제약 행위 시험용 원시 INSERT 문. 값은 Demo 시험 값이며 운영 MID/주문/금액이 아니다. 업무 경로(application/adapter)를 거치지
 * 않고 DB 제약 자체가 무엇을 받고 거부하는지 보기 위한 것이다(업무 경로 시험은 adapter·application spec).
 */
object PaymentSchemaRows {

    const val INSERT_PAYMENT = """
        INSERT INTO payment (payment_id, payment_key, environment, merchant_account_id, merchant_order_id, merchant_site_id,
            amount_minor, currency, payment_method, order_name, order_intent_fingerprint, slot_generation, status,
            eligibility_snapshot_version, created_at, version)
        VALUES (?, ?, ?, ?, ?, 'demo-site-001', ?, ?, 'CARD', NULL, 'v1:fp', ?, 'REQUESTED', 'snap-1', '2026-10-07 00:00:00.000', 0)
    """

    /** payment_id, payment_key, environment, mid, orderId, amount, currency, slotGeneration */
    fun paymentArgs(
        paymentId: String,
        paymentKey: String = "key-$paymentId",
        environment: String = "TEST",
        mid: String,
        orderId: String,
        amount: Long = 10_000,
        currency: String = "KRW",
        slotGeneration: Long = 1,
    ): Array<Any?> = arrayOf(paymentId, paymentKey, environment, mid, orderId, amount, currency, slotGeneration)

    const val INSERT_CONTROL = """
        INSERT INTO order_payment_control (environment, merchant_account_id, merchant_order_id, fixed_intent_fingerprint,
            current_payment_id, generation, authorized_payment_id, created_at, updated_at, version)
        VALUES (?, ?, ?, 'v1:fp', ?, 1, NULL, '2026-10-07 00:00:00.000', '2026-10-07 00:00:00.000', 0)
    """

    const val INSERT_IDEMPOTENCY = """
        INSERT INTO idempotency_request (environment, merchant_account_id, operation, idempotency_key, request_record_id,
            request_fingerprint, result_kind, result_code, result_payment_id, created_at, expires_at, updated_at, version)
        VALUES (?, ?, ?, ?, ?, 'r1:fp', ?, ?, ?, ?, ?, ?, 0)
    """

    /** environment, mid, operation, key, recordId, resultKind, resultCode, resultPaymentId, createdAt, expiresAt, updatedAt */
    fun idempotencyArgs(
        mid: String,
        key: String,
        environment: String = "TEST",
        operation: String = "CreatePayment",
        recordId: String = "rec-" + key,
        resultKind: String = "SUCCESS",
        resultCode: String? = "CREATED",
        resultPaymentId: String? = "pay-x",
        createdAt: String = "2026-10-07 00:00:00.000",
        expiresAt: String = "2026-11-06 00:00:00.000",
    ): Array<Any?> =
        arrayOf(environment, mid, operation, key, recordId, resultKind, resultCode, resultPaymentId, createdAt, expiresAt, createdAt)

    const val INSERT_OUTBOX = """
        INSERT INTO outbox_event (event_id, effect_key, event_type, schema_version, event_category, producer, aggregate_type,
            aggregate_id, aggregate_version, occurred_at, correlation_id, causation_id, payload, publish_status, created_at)
        VALUES (?, ?, 'PaymentRequested', 1, 'INTEGRATION_NON_FINANCIAL', 'payment-api/payment', 'Payment', ?, 0,
            '2026-10-07 00:00:00.000', 'corr', 'cause', JSON_OBJECT('k', 'v'), 'PENDING', '2026-10-07 00:00:00.000')
    """

    const val INSERT_HOLD = """
        INSERT INTO incident_hold (environment, merchant_account_id, merchant_order_id, payment_id, reason, created_at)
        VALUES (?, ?, ?, ?, ?, '2026-10-07 00:00:00.000')
    """
}

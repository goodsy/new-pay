package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.application.provided.CreatePaymentCommand
import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.provided.NewPaymentIdentifiers
import com.paycore.payment.payment.application.provided.RequestFingerprint
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CreatePayment application 시험 Named Fixture(Demo 값). 요청 fingerprint는 **시험용 대역**이다: 내용이 같으면 같고 다르면 다른
 * `r1:<sha256>`을 만들 뿐 계약 §7 r1 정규 표현(CP-API-001 구현·golden vector)과 같다고 주장하지 않는다. 이 Use Case는 r1을 계산하지
 * 않고 저장·비교만 하므로 이 대역으로 충분하다.
 */
object CreatePaymentFixture {

    fun newIdentifiers() = NewPaymentIdentifiers(
        paymentId = PaymentId(unique("pay")),
        paymentKey = PaymentKey(unique("pk")),
        idempotencyRecordId = IdempotencyRecordId(UUID.randomUUID().toString()),
        eventId = EventId(UUID.randomUUID().toString()),
    )

    fun newKey(): IdempotencyKey = IdempotencyKey(unique("idem"))

    fun command(
        order: OrderKey,
        intent: OrderIntent = PersistenceTestData.intent(),
        key: IdempotencyKey = newKey(),
        orderName: String? = "Demo order",
        at: Instant = PersistenceTestData.t0,
        ids: NewPaymentIdentifiers = newIdentifiers(),
    ) = CreatePaymentCommand(
        orderKey = order,
        orderIntent = intent,
        paymentMethod = PaymentMethod.CARD,
        orderName = orderName,
        idempotencyKey = key,
        requestFingerprint = stubRequestFingerprint(order, intent, orderName),
        requestedAt = at,
        correlationId = "req-" + UUID.randomUUID(),
        newIdentifiers = ids,
    )

    private fun stubRequestFingerprint(order: OrderKey, intent: OrderIntent, orderName: String?): RequestFingerprint {
        val content = listOf(
            order.merchantAccountId.value, intent.merchantSiteId.value, order.merchantOrderId.value, intent.money.amountMinor.toString(),
            intent.money.currency.value, PaymentMethod.CARD.name, orderName ?: "<absent>",
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return RequestFingerprint("r1:" + HexFormat.of().formatHex(digest))
    }

    /** 업무 테이블별 행 수(MID 범위). outbox는 payload의 MID로 센다(Payment 행이 없어도 고아 이벤트를 잡는다). */
    fun DbAccess.rowsOf(order: OrderKey): Map<String, Long> {
        val mid = order.merchantAccountId.value
        return linkedMapOf(
            "payment" to count("SELECT COUNT(*) FROM payment WHERE merchant_account_id = ?", mid),
            "order_payment_control" to count("SELECT COUNT(*) FROM order_payment_control WHERE merchant_account_id = ?", mid),
            "idempotency_request" to count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ?", mid),
            "outbox_event" to count("SELECT COUNT(*) FROM outbox_event WHERE payload->>'$.merchantAccountId' = ?", mid),
            "incident_hold" to count("SELECT COUNT(*) FROM incident_hold WHERE merchant_account_id = ?", mid),
        )
    }

    fun rows(payment: Long, control: Long, idempotency: Long, outbox: Long, hold: Long = 0) = linkedMapOf(
        "payment" to payment, "order_payment_control" to control, "idempotency_request" to idempotency, "outbox_event" to outbox,
        "incident_hold" to hold,
    )

    /** [n]개 작업을 동시에 시작한다(각자 자기 DB 연결·TX). 결과는 성공/예외 그대로 모은다. */
    fun <T> concurrently(n: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i -> pool.submit(Callable { start.await(); runCatching { task(i) } }) }
            start.countDown()
            return futures.map { it.get(90, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}

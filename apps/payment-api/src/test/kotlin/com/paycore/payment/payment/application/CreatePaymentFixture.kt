package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.application.provided.CreatePaymentCommand
import com.paycore.payment.payment.application.provided.EventId
import com.paycore.payment.payment.application.provided.IdempotencyRecordId
import com.paycore.payment.payment.application.provided.NewPaymentIdentifiers
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CreatePayment application 시험 Named Fixture(Demo 값). 요청 fingerprint r1은 CP-API-001부터 Use Case가 command 값으로 계산한다
 * (`RequestFingerprints`, golden vector는 `RequestFingerprintV1Spec`). [demoActor]는 시험 전용 Credential keyId다(운영 키 아님).
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
        requestedAt = at,
        correlationId = "req-" + UUID.randomUUID(),
        newIdentifiers = ids,
        actor = demoActor,
    )

    /** 시험 전용 Credential keyId(Demo — 운영 Credential 아님). */
    val demoActor = CredentialKeyId("demoActor0001")

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

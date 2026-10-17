package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.adapter.web.ApiTestTimes
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.demoActor
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenCommand
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenResult
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenUseCase
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionCommand
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionResult
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionUseCase
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import java.time.Duration
import java.time.Instant

/**
 * 결제창 만료 시험 Named Fixture(AUTHN-001 S11, src/test 전용). 시각은 API 시험 시계 [ApiTestTimes.T0] 기준이다: T0에 만든 Payment의 결제창 시각은 T0 + 30분(시험 설정 Demo
 * 유효기간). 결제창 기록은 공식 Use Case(발급·교환)로만 만든다(reconstitute 없음).
 */
class CheckoutExpiryFlow(
    private val create: CreatePaymentUseCase,
    private val issue: IssueCheckoutTokenUseCase,
    private val open: OpenCheckoutSessionUseCase,
    private val db: DbAccess,
) {
    data class Opened(val order: OrderKey, val paymentId: PaymentId, val handle: String, val secret: String)

    /** [at]에 만든 REQUESTED TEST Payment. */
    fun payment(at: Instant = ApiTestTimes.T0, order: OrderKey = PersistenceTestData.newOrder()): Pair<OrderKey, PaymentId> =
        order to (create.create(command(order, at = at)) as CreatePaymentResult.Accepted).payment.paymentId

    /** 발급만 한 토큰 원문(교환 전 — ISSUED). 발급 시각은 시험 시계. */
    fun token(order: OrderKey, id: PaymentId): String =
        (issue.issue(IssueCheckoutTokenCommand(MerchantAuthentication(demoActor, order.merchantAccountId, order.environment), id.value, OK, FAIL, "req-expiry")) as IssueCheckoutTokenResult.Issued)
            .checkoutUrl.substringAfter("token=")

    /** 토큰 발급 + 교환까지 한 결제창(ACTIVE 결제창 세션). */
    fun opened(at: Instant = ApiTestTimes.T0): Opened {
        val (order, id) = payment(at)
        val result = open.open(OpenCheckoutSessionCommand(token(order, id), "req-expiry")) as OpenCheckoutSessionResult.Opened
        return Opened(order, id, result.handle, result.cookieSecret)
    }

    fun status(id: PaymentId): Any? = db.withApp { c -> with(db) { c.scalar("SELECT status FROM payment WHERE payment_id = ?", id.value) } }

    fun expiredEvents(id: PaymentId): List<Map<String, Any?>> =
        db.withApp { c -> with(db) { c.rows("SELECT * FROM outbox_event WHERE effect_key = ?", "PaymentExpired:${id.value}") } }

    fun evidence(id: PaymentId): List<Any?> =
        db.withApp { c -> with(db) { c.rows("SELECT evidence FROM payment_termination_evidence WHERE payment_id = ? ORDER BY seq", id.value) } }.map { it["evidence"] }

    fun tokenStatuses(id: PaymentId): List<Any?> =
        db.withApp { c -> with(db) { c.rows("SELECT status FROM checkout_token WHERE payment_id = ? ORDER BY issued_at, checkout_token_id", id.value) } }.map { it["status"] }

    fun checkoutSessions(id: PaymentId): List<Pair<Any?, Any?>> =
        db.withApp { c -> with(db) { c.rows("SELECT status, close_reason FROM checkout_session WHERE payment_id = ? ORDER BY created_at, checkout_session_id", id.value) } }
            .map { it["status"] to it["close_reason"] }

    fun authSessions(id: PaymentId): List<Pair<Any?, Any?>> =
        db.withApp { c -> with(db) { c.rows("SELECT status, expiry_cause FROM authentication_session WHERE payment_id = ? ORDER BY created_at, authentication_session_id", id.value) } }
            .map { it["status"] to it["expiry_cause"] }

    companion object {
        const val OK = "https://shop.example.test/pay/ok"
        const val FAIL = "https://shop.example.test/pay/fail"

        /** T0에 만든 Payment의 결제창 시각(시험 Demo 유효기간 30분). */
        val WINDOW_END: Instant = ApiTestTimes.T0.plus(Duration.ofMinutes(30))
    }
}

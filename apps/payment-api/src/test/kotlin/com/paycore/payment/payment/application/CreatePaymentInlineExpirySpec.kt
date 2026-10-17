package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.t0
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.provided.CreatePaymentOutcome
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionEventStore
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.AuthenticationEventDetail
import com.paycore.payment.payment.domain.AuthenticationEventKind
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * CreatePayment inline 만료(AUTHN-001 §7.5.3 판정 U-3, D-AUTHN-005·008, n-d·n-e) — 실 MySQL. 같은 주문·같은 내용·다른 키 요청이 결제창 시각이 지난 REQUESTED Payment를
 * 만나면 **같은 TX**에서 `RequestedPaymentClosing`(세션·결제창 세션·토큰 닫기 + PaymentExpired append)을 실행하고 `control.decide` = CreateNewPayment(generation + 1)로
 * 새 Payment(+ PaymentRequested)를 만든다. 결과 code는 CREATED(응답 형식 불변). Outbox 순서는 PaymentExpired(이전) → PaymentRequested(새).
 * 결제창 시각 전이면 기존 Payment 재사용·시각 연장 없음. 실패 주입이면 만료까지 함께 rollback.
 */
@PaymentDbTest
class CreatePaymentInlineExpirySpec(
    private val service: CreatePaymentUseCase,
    private val payments: PaymentRepository,
    private val tokens: CheckoutTokenStore,
    private val checkoutSessions: CheckoutSessionStore,
    private val sessions: AuthenticationSessionRepository,
    private val events: AuthenticationSessionEventStore,
    private val faults: FaultInjector,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach { faults.reset() }

    val tx = TransactionTemplate(transactionManager)
    fun <T> inTx(block: () -> T): T = tx.execute { block() }
    val json = JsonMapper.builder().build()
    val window = Duration.ofMinutes(30) // 시험 설정 Demo 결제 유효기간(checkout-validity-demo-1)

    /** 결제창 기록이 있는 REQUESTED Payment: 소비된 토큰 → ACTIVE 결제창 세션 → CREATED 인증 세션, 그리고 미교환 ISSUED 토큰 하나. */
    data class Seeded(val order: OrderKey, val paymentId: PaymentId, val consumedTokenId: String, val issuedTokenId: String, val checkoutSessionId: String, val authSessionId: String)

    fun seeded(): Seeded {
        val order = newOrder()
        val first = (service.create(command(order, at = t0)) as CreatePaymentResult.Accepted).payment.paymentId
        val payment: Payment = inTx { payments.lock(first)!!.value }
        val t1 = CheckoutPersistenceFixture.token(payment).also { inTx { tokens.insert(it) } }
        inTx { tokens.lock(t1.id)!!.let { tokens.update(it.value.consumed(t1.issuedAt.plusSeconds(5)), it.version) } }
        val s1 = CheckoutPersistenceFixture.session(t1).also { inTx { checkoutSessions.insert(it) } }
        val a1 = CheckoutPersistenceFixture.created(payment).also { inTx { sessions.insertNew(it, CheckoutPersistenceFixture.attributes(s1.id)) } }
        val t2 = CheckoutPersistenceFixture.token(payment).also { inTx { tokens.insert(it) } }
        return Seeded(order, first, t1.id.value, t2.id.value, s1.id.value, a1.id.value)
    }

    fun row(sql: String, vararg args: Any?): Map<String, Any?> = db.withApp { c -> with(db) { c.rows(sql, *args) } }.single()

    fun outboxOf(paymentId: String): List<Map<String, Any?>> =
        db.withApp { c -> with(db) { c.rows("SELECT * FROM outbox_event WHERE aggregate_id = ? ORDER BY id", paymentId) } }

    Given("a REQUESTED payment whose checkout window has elapsed (now = created_at + validity)") {
        Then("the same order with a new key expires it inline, closes its checkout records, appends PaymentExpired first and creates generation 2") {
            val s = seeded()
            val at = t0.plus(window)
            val second = command(s.order, at = at)
            val accepted = service.create(second).shouldBeInstanceOf<CreatePaymentResult.Accepted>()

            accepted.outcome shouldBe CreatePaymentOutcome.CREATED
            accepted.replayed shouldBe false
            accepted.payment.paymentId shouldBe second.newIdentifiers.paymentId

            val old = row("SELECT status, version, checkout_expires_at FROM payment WHERE payment_id = ?", s.paymentId.value)
            old["status"] shouldBe "EXPIRED"
            db.withApp { c -> with(db) { c.rows("SELECT evidence FROM payment_termination_evidence WHERE payment_id = ?", s.paymentId.value) } }
                .map { it["evidence"] } shouldBe listOf("NOT_TRANSMITTED_AND_SESSION_DISCARDED")
            row("SELECT slot_generation, status FROM payment WHERE payment_id = ?", second.newIdentifiers.paymentId.value).let {
                it["slot_generation"].toString() shouldBe "2"
                it["status"] shouldBe "REQUESTED"
            }
            row("SELECT current_payment_id, generation FROM order_payment_control WHERE merchant_account_id = ?", s.order.merchantAccountId.value).let {
                it["current_payment_id"] shouldBe second.newIdentifiers.paymentId.value
                it["generation"].toString() shouldBe "2"
            }

            // 결제창 기록 닫기(같은 TX): 인증 세션 EXPIRED(PAYMENT_CLOSED) + 결정 로그 1행, 결제창 세션 EXPIRED(PAYMENT_CLOSED), 미교환 토큰 CLOSED, 소비된 토큰 불변.
            row("SELECT status, expiry_cause FROM authentication_session WHERE authentication_session_id = ?", s.authSessionId).let {
                it["status"] shouldBe "EXPIRED"
                it["expiry_cause"] shouldBe "PAYMENT_CLOSED"
            }
            inTx { events.findBySession(sessions.lockAllOfPayment(s.paymentId).single().value.id) }.filter { it.kind == AuthenticationEventKind.EXPIRED }
                .map { it.detail } shouldBe listOf(AuthenticationEventDetail.PAYMENT_CLOSED)
            row("SELECT status, close_reason FROM checkout_session WHERE checkout_session_id = ?", s.checkoutSessionId).let {
                it["status"] shouldBe "EXPIRED"
                it["close_reason"] shouldBe "PAYMENT_CLOSED"
            }
            row("SELECT status FROM checkout_token WHERE checkout_token_id = ?", s.issuedTokenId)["status"] shouldBe "CLOSED"
            row("SELECT status FROM checkout_token WHERE checkout_token_id = ?", s.consumedTokenId)["status"] shouldBe "CONSUMED"

            // PaymentExpired 1건(D-AUTHN-008) — PaymentRequested(새 Payment)보다 먼저(n-e).
            val expired = outboxOf(s.paymentId.value).filter { it["event_type"] == "PaymentExpired" }.single()
            val requested = outboxOf(second.newIdentifiers.paymentId.value).single()
            requested["event_type"] shouldBe "PaymentRequested"
            ((expired["id"] as Number).toLong() < (requested["id"] as Number).toLong()) shouldBe true
            expired["schema_version"].toString() shouldBe "1"
            expired["event_category"] shouldBe "INTEGRATION_NON_FINANCIAL"
            expired["producer"] shouldBe "payment-api/payment"
            expired["aggregate_type"] shouldBe "Payment"
            expired["aggregate_version"].toString() shouldBe old["version"].toString()
            expired["effect_key"] shouldBe "PaymentExpired:${s.paymentId.value}"
            expired["correlation_id"] shouldBe second.correlationId
            expired["causation_id"] shouldBe second.newIdentifiers.idempotencyRecordId.value
            (expired["occurred_at"] as java.time.LocalDateTime).toInstant(java.time.ZoneOffset.UTC) shouldBe at

            // payload: PaymentRequested와 같은 기준(CP계약 §10) — 키 집합 정확 비교, paymentKey·orderName·카드·비밀·결제창 식별값 없음.
            val payload = json.readTree(expired["payload"].toString())
            payload.properties().map { it.key }.toSet() shouldBe setOf(
                "environment", "merchantAccountId", "merchantSiteId", "merchantOrderId", "paymentId", "amount", "currency", "paymentMethod",
                "slotGeneration", "lifecycle", "terminationEvidence", "checkoutExpiresAt", "checkoutValidityVersion", "expiredAt",
            )
            payload.get("lifecycle").stringValue() shouldBe "EXPIRED"
            payload.get("terminationEvidence").stringValue() shouldBe "NOT_TRANSMITTED_AND_SESSION_DISCARDED"
            payload.get("paymentId").stringValue() shouldBe s.paymentId.value
            payload.get("merchantOrderId").stringValue() shouldBe s.order.merchantOrderId.value
            payload.get("amount").isIntegralNumber shouldBe true
            payload.get("slotGeneration").longValue() shouldBe 1L
            payload.get("checkoutExpiresAt").stringValue() shouldBe "2026-10-07T01:32:03.456Z"
            payload.get("checkoutValidityVersion").stringValue() shouldBe "checkout-validity-demo-1"
            payload.get("expiredAt").stringValue() shouldBe "2026-10-07T01:32:03.456Z"

            // 같은 키 재요청: 저장 결과 재생(새 Payment), 두 번째 PaymentExpired 없음.
            val replay = service.create(second).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            replay.replayed shouldBe true
            replay.payment.paymentId shouldBe second.newIdentifiers.paymentId
            outboxOf(s.paymentId.value).count { it["event_type"] == "PaymentExpired" } shouldBe 1
        }
    }

    Given("a REQUESTED payment 1 ms before its checkout window ends") {
        Then("the same order reuses it (EXISTING_ACTIVE_PAYMENT) and the window is not extended") {
            val s = seeded()
            val reused = service.create(command(s.order, at = t0.plus(window).minusMillis(1))).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            reused.outcome shouldBe CreatePaymentOutcome.EXISTING_ACTIVE_PAYMENT
            reused.payment.paymentId shouldBe s.paymentId
            row("SELECT status, checkout_expires_at FROM payment WHERE payment_id = ?", s.paymentId.value).let {
                it["status"] shouldBe "REQUESTED"
                (it["checkout_expires_at"] as java.time.LocalDateTime).toInstant(java.time.ZoneOffset.UTC) shouldBe t0.plus(window)
            }
            outboxOf(s.paymentId.value).map { it["event_type"] } shouldBe listOf("PaymentRequested")
            row("SELECT status FROM checkout_token WHERE checkout_token_id = ?", s.issuedTokenId)["status"] shouldBe "ISSUED"
        }
    }

    Given("a failure after the inline expiry was flushed (new payment insert fails)") {
        Then("everything rolls back — the old payment stays REQUESTED with its records open and no PaymentExpired; a retry converges") {
            val s = seeded()
            val at: Instant = t0.plus(window).plusSeconds(1)
            faults.after(FaultPoint.PAYMENT_INSERT) { throw IllegalStateException("injected after new payment insert") }
            shouldThrow<IllegalStateException> { service.create(command(s.order, at = at)) }
            faults.reset()

            row("SELECT status FROM payment WHERE payment_id = ?", s.paymentId.value)["status"] shouldBe "REQUESTED"
            outboxOf(s.paymentId.value).map { it["event_type"] } shouldBe listOf("PaymentRequested")
            row("SELECT status FROM authentication_session WHERE authentication_session_id = ?", s.authSessionId)["status"] shouldBe "CREATED"
            row("SELECT status FROM checkout_session WHERE checkout_session_id = ?", s.checkoutSessionId)["status"] shouldBe "ACTIVE"
            row("SELECT status FROM checkout_token WHERE checkout_token_id = ?", s.issuedTokenId)["status"] shouldBe "ISSUED"

            val retried = service.create(command(s.order, at = at)).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
            retried.outcome shouldBe CreatePaymentOutcome.CREATED
            outboxOf(s.paymentId.value).count { it["event_type"] == "PaymentExpired" } shouldBe 1
        }
    }
})

package com.paycore.payment.payment.application

import com.paycore.payment.payment.domain.fail
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.concurrently
import com.paycore.payment.payment.application.CreatePaymentFixture.demoActor
import com.paycore.payment.payment.application.provided.CheckoutFailCode
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenCommand
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenResult
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenUseCase
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionCommand
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionResult
import com.paycore.payment.payment.application.provided.OpenCheckoutSessionUseCase
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 결제창 토큰 교환 TX-X(AUTHN-001 §4.1·§6.1·§6.3, D-AUTHN-001) — 실 MySQL. 잠금 payment(X) → checkout_session(X) → checkout_token(X), 토큰은 digest로 비잠금 조회 뒤
 * 잠가 재판정한다(검토 #2 m-1 — 진 쪽은 "이미 사용됨"을 결정적으로 본다). 성공: 같은 Payment의 ACTIVE 결제창 세션 REVOKED(SUPERSEDED) → 새 결제창 세션(handle 128bit,
 * 쿠키 비밀 256bit — DB에는 `cks1:` digest만, `expires_at = min(now + 세션 수명, 결제창 시각)`) → 토큰 CONSUMED. 토큰이 없음·사용됨·대체됨·만료·형식 오류는 모두 같은
 * 거절(열거 방지). Payment가 REQUESTED가 아니면 토큰 CLOSED·결제창 세션 없음·Payment 불변(§7.5.3)이고 failUrl(code=PAYMENT_NOT_AVAILABLE)을 준다.
 */
@PaymentDbTest
class CheckoutExchangeSpec(
    private val open: OpenCheckoutSessionUseCase,
    private val issue: IssueCheckoutTokenUseCase,
    private val create: CreatePaymentUseCase,
    private val payments: PaymentRepository,
    private val faults: FaultInjector,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach { faults.reset() }

    val tx = TransactionTemplate(transactionManager)
    val sessionTtl = Duration.ofMinutes(20) // 시험 설정 Demo 결제창 세션 수명(paycore.checkout.session-ttl)
    val failUrl = "https://shop.example.test/pay/fail?x=1"

    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    fun payment(age: Duration): Pair<OrderKey, PaymentId> {
        val order = newOrder()
        val created = create.create(command(order, at = now().minus(age))).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        return order to created.payment.paymentId
    }

    fun token(order: OrderKey, id: PaymentId): String {
        val issued = issue.issue(
            IssueCheckoutTokenCommand(MerchantAuthentication(demoActor, order.merchantAccountId, order.environment), id.value, "https://shop.example.test/pay/ok", failUrl, "req-exchange"),
        ).shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>()
        return issued.checkoutUrl.substringAfter("token=")
    }

    fun exchange(raw: String) = open.open(OpenCheckoutSessionCommand(raw, "req-open"))

    fun sessions(id: PaymentId) = db.withApp { c -> with(db) { c.rows("SELECT * FROM checkout_session WHERE payment_id = ? ORDER BY created_at, checkout_session_id", id.value) } }

    fun tokenStatus(raw: String) = db.withApp { c ->
        with(db) { c.rows("SELECT status FROM checkout_token WHERE token_digest = ?", SecretDigest.of(SecretKind.CHECKOUT_TOKEN, raw).value) }
    }.single()["status"]

    fun Map<String, Any?>.instant(column: String): Instant = (this[column] as LocalDateTime).toInstant(ZoneOffset.UTC)

    Given("an ISSUED token of a REQUESTED payment") {
        Then("exchange opens one checkout session: handle and cookie secret only in the result, digest stored, token CONSUMED, expiry cut at the window") {
            val (order, id) = payment(age = Duration.ofMinutes(15))
            val raw = token(order, id)
            val before = now()
            val opened = exchange(raw).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()

            opened.handle shouldMatch Regex("^[A-Za-z0-9_-]{22}$")
            opened.cookieSecret shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
            val row = sessions(id).single()
            row["status"] shouldBe "ACTIVE"
            row["handle"] shouldBe opened.handle
            row["session_digest"] shouldBe SecretDigest.of(SecretKind.CHECKOUT_SESSION, opened.cookieSecret).value
            row["fail_url"] shouldBe failUrl
            row["site_host"] shouldBe ScriptedSiteWebHostPort.DEMO_HOST
            row["policy_version"] shouldBe "checkout-demo-1"
            (row["checkout_session_id"] as String) shouldMatch Regex("^cks_[0-9A-Z]{26}$")
            // 남은 결제창 15분 < 세션 수명 20분 → 결제창 시각으로 자른다(m-6). 쿠키 Max-Age = 남은 초(내림, 최소 1).
            val window = db.withApp { c -> with(db) { c.rows("SELECT checkout_expires_at FROM payment WHERE payment_id = ?", id.value) } }.single().instant("checkout_expires_at")
            row.instant("expires_at") shouldBe window
            opened.expiresAt shouldBe window
            opened.maxAgeSeconds shouldBeLessThanOrEqual Duration.between(before, window).seconds
            opened.maxAgeSeconds shouldBeGreaterThan Duration.ofMinutes(14).seconds
            tokenStatus(raw) shouldBe "CONSUMED"
            sessions(id).none { it.values.any { v -> v == opened.cookieSecret } } shouldBe true
        }

        Then("with plenty of window left the session lives for the configured TTL") {
            val (order, id) = payment(age = Duration.ZERO)
            val before = now()
            val opened = exchange(token(order, id)).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()
            (opened.expiresAt >= before.plus(sessionTtl) && opened.expiresAt <= now().plus(sessionTtl)) shouldBe true
        }

        Then("a used, unknown, malformed or expired token is the same InvalidToken (no state change)") {
            val (order, id) = payment(age = Duration.ZERO)
            val raw = token(order, id)
            exchange(raw).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()
            listOf(raw, "ckt_" + "A".repeat(43), "ckt_short", "", "x".repeat(47), raw + "x", raw.uppercase()).forEach { candidate ->
                withClue(candidate.take(12)) { exchange(candidate) shouldBe OpenCheckoutSessionResult.InvalidToken }
            }
            sessions(id).size shouldBe 1
            tokenStatus(raw) shouldBe "CONSUMED"
        }
    }

    Given("C2: a newer token of the same payment is exchanged") {
        Then("the new checkout session is the only ACTIVE one; the previous one is REVOKED(SUPERSEDED)") {
            val (order, id) = payment(age = Duration.ZERO)
            val first = exchange(token(order, id)).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()
            val second = exchange(token(order, id)).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()
            sessions(id).associate { it["handle"] to (it["status"] to it["close_reason"]) } shouldBe mapOf(
                first.handle to ("REVOKED" to "SUPERSEDED"),
                second.handle to ("ACTIVE" to null),
            )
        }
    }

    Given("a payment that is no longer REQUESTED while its token is still ISSUED") {
        Then("the token is CLOSED, no session is created, the payment is unchanged and the fail URL carries PAYMENT_NOT_AVAILABLE") {
            val (order, id) = payment(age = Duration.ZERO)
            val raw = token(order, id)
            tx.executeWithoutResult { payments.lock(id)!!.let { p -> p.value.startAuthorization(); p.value.fail(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED); payments.update(p.value, p.version) } }
            val result = exchange(raw).shouldBeInstanceOf<OpenCheckoutSessionResult.NotAvailable>()
            result.code shouldBe CheckoutFailCode.PAYMENT_NOT_AVAILABLE
            result.failRedirect shouldBe "$failUrl&paymentId=${id.value}&code=PAYMENT_NOT_AVAILABLE"
            tokenStatus(raw) shouldBe "CLOSED"
            sessions(id) shouldBe emptyList()
            db.withApp { c -> with(db) { c.rows("SELECT status FROM payment WHERE payment_id = ?", id.value) } }.single()["status"] shouldBe "FAILED"
        }
    }

    Given("C1: the same token exchanged 8 times concurrently") {
        Then("exactly one checkout session and one cookie; the 7 losers are a clean InvalidToken (no conflict error, no retry dependence)") {
            val (order, id) = payment(age = Duration.ZERO)
            val raw = token(order, id)
            val results = concurrently(8) { exchange(raw) }.map { it.getOrThrow() }
            results.count { it is OpenCheckoutSessionResult.Opened } shouldBe 1
            results.count { it == OpenCheckoutSessionResult.InvalidToken } shouldBe 7
            sessions(id).size shouldBe 1
            tokenStatus(raw) shouldBe "CONSUMED"
        }
    }

    Given("a failure after the new checkout session was flushed") {
        Then("the TX rolls back: token still ISSUED, no new session, the earlier session still ACTIVE") {
            val (order, id) = payment(age = Duration.ZERO)
            val first = exchange(token(order, id)).shouldBeInstanceOf<OpenCheckoutSessionResult.Opened>()
            val raw = token(order, id)
            faults.after(FaultPoint.CHECKOUT_SESSION_INSERT) { throw IllegalStateException("injected after checkout session insert") }
            shouldThrow<IllegalStateException> { exchange(raw) }
            faults.reset()
            tokenStatus(raw) shouldBe "ISSUED"
            sessions(id).map { it["handle"] to it["status"] } shouldBe listOf(first.handle to "ACTIVE")
        }
    }
})

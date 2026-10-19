package com.paycore.payment.payment.application

import com.paycore.payment.payment.domain.fail
import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.persistence.PaymentDbTest
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData.newOrder
import com.paycore.payment.payment.application.CreatePaymentFixture.command
import com.paycore.payment.payment.application.CreatePaymentFixture.concurrently
import com.paycore.payment.payment.application.CreatePaymentFixture.demoActor
import com.paycore.payment.payment.application.provided.CheckoutTokenRejection
import com.paycore.payment.payment.application.provided.CreatePaymentResult
import com.paycore.payment.payment.application.provided.CreatePaymentUseCase
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenCommand
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenResult
import com.paycore.payment.payment.application.provided.IssueCheckoutTokenUseCase
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.adapter.persistence.IncidentReporter
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.NoWebHostCause
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.application.required.SiteWebHostUnavailable
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import com.paycore.payment.payment.domain.TerminationEvidence
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
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
 * 결제창 토큰 발급 TX-I(AUTHN-001 §5, D-AUTHN-001·002·005, 판정 U-3·n-d) — 실 MySQL, 운영 서비스 bean(시계는 운영 시계 — 시험 Payment를 "지금" 기준 과거에 만든다).
 * Site WEB host Port는 시험 대역(Demo host `shop.example.test`). 토큰 원문은 응답(checkoutUrl)에만, DB에는 `cth1:` digest만. 미교환 토큰 Payment당 1(AN-04 — 앞 토큰 대체).
 * 판정 순서 §5.2: URL 문법(400) → Payment 소유(404) → 인증 backend(422) → Site host(503/422) → 잠금·inline 만료(커밋 + 409) → hold → 상태 → URL host(422) → 대체 → INSERT.
 */
@PaymentDbTest
class CheckoutTokenIssuanceSpec(
    private val issue: IssueCheckoutTokenUseCase,
    private val create: CreatePaymentUseCase,
    private val incidents: IncidentReporter,
    private val payments: PaymentRepository,
    private val controls: OrderPaymentControlRepository,
    private val tokens: CheckoutTokenStore,
    private val hosts: ScriptedSiteWebHostPort,
    private val faults: FaultInjector,
    private val transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) : BehaviorSpec({

    afterEach { faults.reset() }

    val tx = TransactionTemplate(transactionManager)
    val validity = Duration.ofMinutes(30) // 시험 설정 Demo 결제 유효기간
    val tokenTtl = Duration.ofMinutes(10) // 시험 설정 Demo 토큰 수명(paycore.checkout.token-ttl)
    val ok = "https://shop.example.test/pay/ok?order=1"
    val fail = "https://shop.example.test/pay/fail"

    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    fun auth(order: OrderKey, environment: Environment = order.environment) = MerchantAuthentication(demoActor, order.merchantAccountId, environment)

    /** "지금"보다 [age] 전에 만든 REQUESTED Payment(결제창 시각 = 생성 + 30분). */
    fun payment(age: Duration, order: OrderKey = newOrder()): Pair<OrderKey, PaymentId> {
        val created = create.create(command(order, at = now().minus(age))).shouldBeInstanceOf<CreatePaymentResult.Accepted>()
        return order to created.payment.paymentId
    }

    fun request(order: OrderKey, paymentId: String, success: String = ok, failure: String = fail, environment: Environment = order.environment) =
        IssueCheckoutTokenCommand(auth(order, environment), paymentId, success, failure, "req-issue-" + paymentId.filter { it in '!'..'~' }.takeLast(12))

    fun tokenRows(paymentId: PaymentId): List<Map<String, Any?>> =
        db.withApp { c -> with(db) { c.rows("SELECT * FROM checkout_token WHERE payment_id = ? ORDER BY issued_at, checkout_token_id", paymentId.value) } }

    fun rawTokenOf(issued: IssueCheckoutTokenResult.Issued): String = issued.checkoutUrl.substringAfter("/checkout?token=")

    fun Map<String, Any?>.instant(column: String): Instant = (this[column] as LocalDateTime).toInstant(ZoneOffset.UTC)

    Given("a REQUESTED TEST payment of the caller with a web site host") {
        Then("a token is issued: raw value only in the checkoutUrl, digest stored with the binding, expiry cut at the payment window") {
            val (order, id) = payment(age = Duration.ofMinutes(25))
            val issued = issue.issue(request(order, id.value)).shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>()

            issued.paymentId shouldBe id.value
            issued.checkoutUrl shouldMatch Regex("^https://checkout\\.example\\.test/checkout\\?token=ckt_[A-Za-z0-9_-]{43}$")
            val row = tokenRows(id).single()
            row["status"] shouldBe "ISSUED"
            row["token_digest"] shouldBe SecretDigest.of(SecretKind.CHECKOUT_TOKEN, rawTokenOf(issued)).value
            (row["checkout_token_id"] as String) shouldMatch Regex("^ctk_[0-9A-Z]{26}$")
            row["environment"] shouldBe "TEST"
            row["merchant_account_id"] shouldBe order.merchantAccountId.value
            row["merchant_order_id"] shouldBe order.merchantOrderId.value
            row["merchant_site_id"] shouldBe "demo-site-001"
            row["amount_minor"].toString() shouldBe "10000"
            row["currency"] shouldBe "KRW"
            row["success_url"] shouldBe ok
            row["fail_url"] shouldBe fail
            row["site_host"] shouldBe ScriptedSiteWebHostPort.DEMO_HOST
            row["site_snapshot_version"] shouldBe ScriptedSiteWebHostPort.DEMO_SNAPSHOT_VERSION
            row["issued_by_credential_id"] shouldBe demoActor.value
            row["policy_version"] shouldBe "checkout-demo-1"
            // 남은 결제창 시간(5분) < 토큰 수명(10분) → 결제창 시각으로 자른다(D-AUTHN-005).
            val window = db.withApp { c -> with(db) { c.rows("SELECT checkout_expires_at FROM payment WHERE payment_id = ?", id.value) } }.single().instant("checkout_expires_at")
            row.instant("expires_at") shouldBe window
            issued.expiresAt shouldBe window
            tokenRows(id).none { it.values.any { v -> v == rawTokenOf(issued) } } shouldBe true
        }

        Then("with plenty of window left the token lives for the configured TTL") {
            val (order, id) = payment(age = Duration.ZERO)
            val before = now()
            val issued = issue.issue(request(order, id.value)).shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>()
            val after = now()
            (issued.expiresAt >= before.plus(tokenTtl) && issued.expiresAt <= after.plus(tokenTtl)) shouldBe true
        }

        Then("issuing again supersedes the unexchanged token (one ISSUED per payment); an already expired one is closed as EXPIRED") {
            val (order, id) = payment(age = Duration.ofMinutes(25))
            issue.issue(request(order, id.value)).shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>()
            issue.issue(request(order, id.value)).shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>()
            tokenRows(id).map { it["status"].toString() }.sorted() shouldBe listOf("ISSUED", "SUPERSEDED")
            (tokenRows(id).single { it["status"] == "SUPERSEDED" }["closed_at"] != null) shouldBe true

            val (order2, id2) = payment(age = Duration.ofMinutes(25))
            val stored = tx.execute { payments.lock(id2)!!.value }
            val createdAt = now().minus(Duration.ofMinutes(25))
            val stale = CheckoutPersistenceFixture.token(stored).copy(issuedAt = createdAt, expiresAt = createdAt.plusSeconds(2))
            tx.execute { tokens.insert(stale) }
            issue.issue(request(order2, id2.value)).shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>()
            tokenRows(id2).associate { it["checkout_token_id"] to it["status"] }[stale.id.value] shouldBe "EXPIRED"
            tokenRows(id2).count { it["status"] == "ISSUED" } shouldBe 1
        }
    }

    Given("requests that are rejected before any change") {
        Then("return URL syntax violations are INVALID_RETURN_URL naming the field (checked before the payment is read)") {
            val (order, id) = payment(age = Duration.ZERO)
            issue.issue(request(order, id.value, success = "http://shop.example.test/ok")) shouldBe
                IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.INVALID_RETURN_URL, "successUrl")
            issue.issue(request(order, id.value, failure = "https://shop.example.test:8443/fail")) shouldBe
                IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.INVALID_RETURN_URL, "failUrl")
            issue.issue(request(order, "pay_unknown_" + id.value.takeLast(8), success = "nope")) shouldBe
                IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.INVALID_RETURN_URL, "successUrl")
            tokenRows(id) shouldBe emptyList()
        }

        Then("unknown, malformed, other-MID and other-environment payments are all PAYMENT_NOT_FOUND (no existence oracle)") {
            val (order, id) = payment(age = Duration.ZERO)
            val other = newOrder()
            listOf(
                request(order, "pay_does_not_exist_1"),
                request(order, ""),
                request(order, "p".repeat(65)),
                request(order, "pay_\u0001ctl"),
                IssueCheckoutTokenCommand(auth(other), id.value, ok, fail, "req-other-mid"),
                request(order, id.value, environment = Environment.LIVE),
            ).forEach { withClue(it.paymentId.take(20) + "/" + it.authentication.environment) {
                issue.issue(it) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.PAYMENT_NOT_FOUND, null)
            } }
            tokenRows(id) shouldBe emptyList()
        }

        Then("a LIVE payment has no authentication backend: CHECKOUT_NOT_SUPPORTED") {
            val (order, id) = payment(age = Duration.ZERO, order = newOrder(Environment.LIVE))
            issue.issue(request(order, id.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.CHECKOUT_NOT_SUPPORTED, null)
        }

        Then("site host: undecidable → CHECKOUT_UNAVAILABLE, no web host or another host → RETURN_URL_NOT_ALLOWED") {
            val (order, id) = payment(age = Duration.ZERO)
            hosts.respondFor(order.merchantAccountId) { _, _ -> SiteWebHostResult.Unavailable(SiteWebHostUnavailable.SNAPSHOT_EXPIRED) }
            issue.issue(request(order, id.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.CHECKOUT_UNAVAILABLE, null)
            hosts.respondFor(order.merchantAccountId) { _, _ -> SiteWebHostResult.NoWebHost(NoWebHostCause.APP_CHANNEL) }
            issue.issue(request(order, id.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.RETURN_URL_NOT_ALLOWED, null)
            hosts.respondFor(order.merchantAccountId, ScriptedSiteWebHostPort::demo)
            issue.issue(request(order, id.value, failure = "https://other.example.test/fail")) shouldBe
                IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.RETURN_URL_NOT_ALLOWED, null)
            tokenRows(id) shouldBe emptyList()
        }

        Then("a payment whose authorization started is PAYMENT_AUTHORIZATION_STARTED; a failed one is PAYMENT_TERMINATED") {
            val (order, id) = payment(age = Duration.ZERO)
            tx.executeWithoutResult {
                val control = controls.lock(order)!!
                val p = payments.lock(id)!!
                control.value.startAuthorization(p.value)
                payments.update(p.value, p.version)
            }
            issue.issue(request(order, id.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.PAYMENT_AUTHORIZATION_STARTED, null)

            val (order2, id2) = payment(age = Duration.ZERO)
            tx.executeWithoutResult { payments.lock(id2)!!.let { p -> p.value.startAuthorization(); p.value.fail(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED); payments.update(p.value, p.version) } }
            issue.issue(request(order2, id2.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.PAYMENT_TERMINATED, null)
        }

        Then("an order on incident hold is ORDER_ON_HOLD") {
            val order = newOrder()
            val (_, first) = payment(age = Duration.ZERO, order = order)
            tx.executeWithoutResult { payments.lock(first)!!.let { p -> p.value.startAuthorization(); p.value.fail(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED); payments.update(p.value, p.version) } }
            val (_, second) = payment(age = Duration.ZERO, order = order)
            incidents.report(order, first, now())
            issue.issue(request(order, second.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.ORDER_ON_HOLD, null)
            tokenRows(second) shouldBe emptyList()
        }
    }

    Given("a REQUESTED payment whose checkout window has elapsed (U-3, n-d)") {
        Then("issuance expires it inline and COMMITS the expiry (PaymentExpired 1, unexchanged token CLOSED), answering PAYMENT_TERMINATED; a repeat adds nothing") {
            val (order, id) = payment(age = Duration.ofMinutes(31))
            val stored = tx.execute { payments.lock(id)!!.value }
            val createdAt = now().minus(Duration.ofMinutes(31))
            val open = CheckoutPersistenceFixture.token(stored).copy(issuedAt = createdAt.plusSeconds(1), expiresAt = createdAt.plusSeconds(600))
            tx.execute { tokens.insert(open) }

            issue.issue(request(order, id.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.PAYMENT_TERMINATED, null)
            db.withApp { c -> with(db) { c.rows("SELECT status FROM payment WHERE payment_id = ?", id.value) } }.single()["status"] shouldBe "EXPIRED"
            tokenRows(id).single()["status"] shouldBe "CLOSED"
            fun expiredEvents() = db.withApp { c -> with(db) { c.rows("SELECT correlation_id, causation_id FROM outbox_event WHERE effect_key = ?", "PaymentExpired:${id.value}") } }
            expiredEvents().single().let { it["correlation_id"] shouldBe "req-issue-" + id.value.takeLast(12); it["causation_id"] shouldBe "req-issue-" + id.value.takeLast(12) }

            issue.issue(request(order, id.value)) shouldBe IssueCheckoutTokenResult.Rejected(CheckoutTokenRejection.PAYMENT_TERMINATED, null)
            expiredEvents().size shouldBe 1
            tokenRows(id).single()["status"] shouldBe "CLOSED"
        }
    }

    Given("C9: two concurrent issuances for the same payment (merchant retry)") {
        Then("both succeed and exactly one token stays ISSUED, the other SUPERSEDED") {
            val (order, id) = payment(age = Duration.ZERO)
            val results = concurrently(2) { issue.issue(request(order, id.value)) }
            results.map { it.getOrThrow() }.all { it is IssueCheckoutTokenResult.Issued } shouldBe true
            tokenRows(id).map { it["status"] }.sortedBy { it.toString() } shouldBe listOf("ISSUED", "SUPERSEDED")
        }
    }

    Given("a failure after the new token was flushed") {
        Then("the TX rolls back: no new token and the previous token is still ISSUED (not superseded)") {
            val (order, id) = payment(age = Duration.ZERO)
            val first = issue.issue(request(order, id.value)).shouldBeInstanceOf<IssueCheckoutTokenResult.Issued>()
            faults.after(FaultPoint.TOKEN_INSERT) { throw IllegalStateException("injected after token insert") }
            shouldThrow<IllegalStateException> { issue.issue(request(order, id.value)) }
            faults.reset()
            tokenRows(id).map { it["status"] to it["token_digest"] } shouldBe
                listOf("ISSUED" to SecretDigest.of(SecretKind.CHECKOUT_TOKEN, rawTokenOf(first)).value)
            tx.execute { tokens.lockIssuedOfPayment(id) }!!.value.status shouldBe CheckoutTokenStatus.ISSUED
        }
    }
})

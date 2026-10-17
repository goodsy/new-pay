package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.application.ScriptedSiteWebHostPort
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.application.required.SiteWebHostUnavailable
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager

/**
 * 결제창 토큰 발급 공개 API(AUTHN-001 §5.1·§5.3, D-AUTHN-001, AN-01·02·03·05, 검토 n-8) — 실서버·실 MySQL·운영 Credential 경로. `POST /v1/payments/{paymentId}/checkout`
 * (Bearer, 본문 `{successUrl, failUrl}`). 201 본문은 닫힌 키 3개, `Cache-Control: no-store`. 오류는 고정 code·문구(URL·host·내부 사유 echo 없음).
 * Idempotency-Key 헤더는 받지 않는다(400 — 효과 멱등 §5.4). checkoutUrl origin은 설정에서만(m-9 — 요청 헤더 무시).
 */
@PaymentApiTest
class CheckoutTokenApiSpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val clock: MutablePaymentClock,
    private val hosts: ScriptedSiteWebHostPort,
    payments: JpaPaymentRepository,
    controls: JpaOrderPaymentControlRepository,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    afterEach { clock.reset() }

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val s = ApiSpecSupport(db, payments, controls, transactionManager)
    val okUrl = "https://shop.example.test/pay/ok?order=1"
    val failUrl = "https://shop.example.test/pay/fail"

    fun body(success: String = okUrl, failure: String = failUrl) = "{\"successUrl\":\"$success\",\"failUrl\":\"$failure\"}"

    fun newPayment(cred: TestCredentials.Issued): String {
        val r = api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), cred.key, s.newKey())
        r.status shouldBe 201
        return s.json(r.body).get("paymentId").stringValue()
    }

    fun checkout(cred: TestCredentials.Issued?, paymentId: String, json: String = body(), headers: Map<String, String> = mapOf("Content-Type" to "application/json")) =
        api.post("/v1/payments/$paymentId/checkout", json, cred?.key, headers = headers)

    fun error(r: ApiClient.Response): Pair<Int, String> = r.status to s.json(r.body).get("error").get("code").stringValue()

    fun tokenCount(paymentId: String): Long = db.count("SELECT COUNT(*) FROM checkout_token WHERE payment_id = ?", paymentId)

    Given("a TEST merchant credential and its REQUESTED payment") {
        Then("201 with exactly paymentId, checkoutUrl (configured origin) and expiresAt; no-store; forwarded host headers are ignored") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val paymentId = newPayment(cred)
            val r = checkout(cred, paymentId, headers = mapOf("Content-Type" to "application/json", "X-Forwarded-Host" to "evil.example.test", "Forwarded" to "host=evil.example.test"))

            r.status shouldBe 201
            r.header("Cache-Control") shouldBe "no-store"
            (r.header("PayCore-Request-Id") != null) shouldBe true
            val json = s.json(r.body)
            json.properties().map { it.key }.toSet() shouldBe setOf("paymentId", "checkoutUrl", "expiresAt")
            json.get("paymentId").stringValue() shouldBe paymentId
            json.get("checkoutUrl").stringValue() shouldMatch Regex("^https://checkout\\.example\\.test/checkout\\?token=ckt_[A-Za-z0-9_-]{43}$")
            json.get("expiresAt").stringValue() shouldBe "2026-10-14T01:12:03.456Z" // T0 + 토큰 수명 10분(결제창 30분 안)
            r.body shouldNotContain "evil.example.test"
            tokenCount(paymentId) shouldBe 1L
        }

        Then("an Idempotency-Key header is refused with 400 INVALID_REQUEST(field Idempotency-Key) — call again without it (n-8)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val paymentId = newPayment(cred)
            val r = api.post("/v1/payments/$paymentId/checkout", body(), cred.key, idempotencyKey = "idem-checkout-1")
            error(r) shouldBe (400 to "INVALID_REQUEST")
            s.json(r.body).get("error").get("field").stringValue() shouldBe "Idempotency-Key"
            tokenCount(paymentId) shouldBe 0L
        }

        Then("malformed bodies and media types are 400 INVALID_REQUEST; URL syntax violations are 400 INVALID_RETURN_URL naming the field, never echoing it") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val paymentId = newPayment(cred)
            listOf(
                "", "[]", "{}", "{\"successUrl\":\"$okUrl\"}", "{\"successUrl\":null,\"failUrl\":\"$failUrl\"}", "{\"successUrl\":1,\"failUrl\":\"$failUrl\"}",
                "{\"successUrl\":\"$okUrl\",\"failUrl\":\"$failUrl\",\"extra\":\"x\"}", "{\"successUrl\":\"$okUrl\",\"successUrl\":\"$okUrl\",\"failUrl\":\"$failUrl\"}",
                body() + " {}",
            ).forEach { b -> withClue(b) { error(checkout(cred, paymentId, b)) shouldBe (400 to "INVALID_REQUEST") } }
            error(checkout(cred, paymentId, headers = mapOf("Content-Type" to "text/plain"))) shouldBe (400 to "INVALID_REQUEST")

            val bad = checkout(cred, paymentId, body(success = "http://shop.example.test/insecure-marker"))
            error(bad) shouldBe (400 to "INVALID_RETURN_URL")
            s.json(bad.body).get("error").get("field").stringValue() shouldBe "successUrl"
            bad.body shouldNotContain "insecure-marker"
            error(checkout(cred, paymentId, body(failure = "https://shop.example.test/a#frag"))).second shouldBe "INVALID_RETURN_URL"
            tokenCount(paymentId) shouldBe 0L
        }

        Then("no credential is 401; unknown, malformed, too long and other-merchant payment ids are all 404 PAYMENT_NOT_FOUND") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val paymentId = newPayment(cred)
            error(checkout(null, paymentId)) shouldBe (401 to "UNAUTHENTICATED")
            val other = TestCredentials.issue(db, environment = Environment.TEST)
            listOf("pay_unknown_000001", "p".repeat(65), "pay%20space").forEach { id -> withClue(id) { error(checkout(cred, id)) shouldBe (404 to "PAYMENT_NOT_FOUND") } }
            error(checkout(other, paymentId)) shouldBe (404 to "PAYMENT_NOT_FOUND")
            tokenCount(paymentId) shouldBe 0L
        }

        Then("business rejections map to fixed codes: host mismatch 422, LIVE 422, undecidable host 503 retryable with Retry-After, elapsed window 409") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val paymentId = newPayment(cred)
            val mismatch = checkout(cred, paymentId, body(success = "https://other.example.test/ok"))
            error(mismatch) shouldBe (422 to "RETURN_URL_NOT_ALLOWED")
            mismatch.body shouldNotContain "other.example.test"
            mismatch.body shouldNotContain ScriptedSiteWebHostPort.DEMO_HOST

            val live = TestCredentials.issue(db, environment = Environment.LIVE)
            error(checkout(live, newPayment(live))) shouldBe (422 to "CHECKOUT_NOT_SUPPORTED")

            hosts.respondFor(MerchantAccountId(cred.mid)) { _, _ -> SiteWebHostResult.Unavailable(SiteWebHostUnavailable.SNAPSHOT_MISSING) }
            val unavailable = checkout(cred, paymentId)
            error(unavailable) shouldBe (503 to "CHECKOUT_UNAVAILABLE")
            s.json(unavailable.body).get("error").get("retryable").booleanValue() shouldBe true
            unavailable.header("Retry-After") shouldBe "1"
            unavailable.body shouldNotContain "SNAPSHOT_MISSING"
            hosts.respondFor(MerchantAccountId(cred.mid), ScriptedSiteWebHostPort::demo)

            clock.set(ApiTestTimes.T0.plusSeconds(1800))
            val terminated = checkout(cred, paymentId)
            error(terminated) shouldBe (409 to "PAYMENT_TERMINATED")
            s.json(terminated.body).get("error").get("retryable").booleanValue() shouldBe false
            s.scalar("SELECT status FROM payment WHERE payment_id = ?", paymentId) shouldBe "EXPIRED"
        }
    }
})

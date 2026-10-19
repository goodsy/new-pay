package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.domain.fail
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.domain.Environment
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager

/**
 * 결제창 connector·보안 체인·교환·화면(AUTHN-001 §6.1~§6.3, §12, D-AUTHN-001·006, 판정 m-1·m-9) — 실서버 3 connector·실 MySQL.
 * - connector 격리(AN-15): `/checkout` 아래는 checkout 포트에서만, checkout 포트는 `/checkout` 아래만. 쿠키로 `/v1` 아래 인증 불가.
 * - 교환: 303 `/checkout/s/{handle}` + `Set-Cookie: __Host-pc_checkout_<handle>`(Secure·HttpOnly·SameSite=Lax·Path=/·Max-Age). 실패는 고정 문구(사유 구분 없음).
 * - 화면: handle 쿠키 필수(없음·틀림·다른 handle → 401), 가맹점 입력은 escape(XSS), 응답 헤더(CSP·X-Frame-Options·nosniff·no-referrer·no-store·Permissions-Policy).
 * - CSRF: POST는 쿠키 비밀에서 파생한 토큰 필수(없음·틀림 → 403, AN-17). origin은 설정에서만(조작한 Host가 응답에 나타나지 않음).
 */
@PaymentApiTest
class CheckoutWebSpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val checkoutConnector: CheckoutConnector,
    private val internalConnector: InternalConnector,
    private val clock: MutablePaymentClock,
    private val eligibility: com.paycore.payment.payment.application.RecordingEligibilityPort,
    private val expiry: com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsUseCase,
    payments: JpaPaymentRepository,
    controls: JpaOrderPaymentControlRepository,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val publicPort = env.getRequiredProperty("local.server.port").toInt()
    val api = ApiClient(publicPort)
    val web = CheckoutClient(checkoutConnector.localPort)
    val s = ApiSpecSupport(db, payments, controls, transactionManager)
    val csp = "default-src 'none'; style-src 'self'; img-src 'self'; script-src 'none'; base-uri 'none'; frame-ancestors 'none'; " +
        "form-action 'self' https://sim.example.test https://shop.example.test"

    fun newToken(cred: TestCredentials.Issued, orderName: String = "Demo order", amount: Long = 10_000, currency: String = "KRW"): Pair<String, String> {
        val created = api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId(), orderName = orderName, amount = amount, currency = currency), cred.key, s.newKey())
        created.status shouldBe 201
        val paymentId = s.json(created.body).get("paymentId").stringValue()
        val issued = api.post("/v1/payments/$paymentId/checkout", "{\"successUrl\":\"https://shop.example.test/ok\",\"failUrl\":\"https://shop.example.test/fail\"}", cred.key)
        issued.status shouldBe 201
        return paymentId to s.json(issued.body).get("checkoutUrl").stringValue().substringAfter("token=")
    }

    /** 교환 → (handle, `이름=값` 쿠키). */
    fun opened(token: String): Pair<String, String> {
        val r = web.get("/checkout?token=$token")
        r.status shouldBe 303
        val handle = r.header("Location")!!.removePrefix("/checkout/s/")
        return handle to CheckoutClient.cookiePair(r.header("Set-Cookie")!!)
    }

    fun securityHeaders(r: CheckoutClient.Response, expectedCsp: String? = csp) {
        expectedCsp?.let { r.header("Content-Security-Policy") shouldBe it }
        r.header("X-Frame-Options") shouldBe "DENY"
        r.header("X-Content-Type-Options") shouldBe "nosniff"
        r.header("Referrer-Policy") shouldBe "no-referrer"
        r.header("Cache-Control") shouldBe "no-store"
        r.header("Permissions-Policy") shouldBe "camera=(), microphone=(), geolocation=(), payment=()"
    }

    Given("a TEST payment with an issued token") {
        Then("exchange answers 303 to the handle path with a __Host- cookie bound to that handle; the token leaves the URL") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (_, token) = newToken(cred)
            val r = web.get("/checkout?token=$token")
            r.status shouldBe 303
            val location = r.header("Location")!!
            location shouldMatch Regex("^/checkout/s/[A-Za-z0-9_-]{22}$")
            val handle = location.removePrefix("/checkout/s/")
            val cookie = r.header("Set-Cookie")!!
            cookie shouldMatch Regex("^__Host-pc_checkout_${Regex.escape(handle)}=[A-Za-z0-9_-]{43}; .*")
            listOf("Max-Age=1200", "Path=/", "Secure", "HttpOnly", "SameSite=Lax").forEach { attr -> withClue(attr) { cookie shouldContain attr } }
            cookie shouldNotContain "Domain="
            securityHeaders(r, expectedCsp = null)
            r.body shouldNotContain token
        }

        Then("the page needs that handle's cookie; it shows escaped merchant input, the site host, card companies and a CSRF field, with the security headers") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (_, token) = newToken(cred, orderName = "<script>alert(1)</script>주문")
            val (handle, cookie) = opened(token)
            val page = web.get("/checkout/s/$handle", cookie)
            page.status shouldBe 200
            page.header("Content-Type")!! shouldStartWith "text/html"
            page.body shouldContain "&lt;script&gt;alert(1)&lt;/script&gt;주문"
            page.body shouldNotContain "<script"
            page.body shouldContain "shop.example.test"
            page.body shouldContain "가상카드A"
            page.body shouldContain "10,000"
            page.body shouldContain "name=\"_csrf\""
            securityHeaders(page)

            web.get("/checkout/s/$handle").let { it.status shouldBe 401; it.body shouldContain "결제창 세션이 만료되었습니다." }
            web.get("/checkout/s/$handle", cookie.substringBefore('=') + "=" + "A".repeat(43)).status shouldBe 401
            val (otherHandle, otherCookie) = opened(newToken(cred).second)
            web.get("/checkout/s/$handle", otherCookie).status shouldBe 401
            web.get("/checkout/s/$otherHandle", "$cookie; $otherCookie").status shouldBe 200 // 두 결제창 쿠키가 섞이지 않는다(m-1)
            web.get("/checkout/s/$handle", "$cookie; $otherCookie").status shouldBe 200
            web.get("/checkout/s/short", cookie).let { it.status shouldBe 400; securityHeaders(it, expectedCsp = null) }
        }

        Then("a reused, missing or malformed token and unknown query names are the same fixed 400 page without internal detail") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (_, token) = newToken(cred)
            opened(token)
            listOf("/checkout?token=$token", "/checkout", "/checkout?token=bad", "/checkout?token=$token&x=1", "/checkout?token=$token&token=$token").forEach { path ->
                withClue(path.take(30)) {
                    val r = web.get(path)
                    r.status shouldBe 400
                    r.body shouldContain "결제창을 열 수 없습니다. 가맹점에서 다시 시도해 주세요."
                    listOf("Exception", "SQL", "at com.", token).forEach { leak -> r.body shouldNotContain leak }
                    r.header("Set-Cookie") shouldBe null
                }
            }
        }

        Then("POST without or with a wrong CSRF token is 403 with no state change; the token is derived from that handle's cookie secret") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (_, token) = newToken(cred)
            val (handle, cookie) = opened(token)
            web.post("/checkout/s/$handle/cancel", emptyMap(), cookie).let { it.status shouldBe 403; it.body shouldContain "요청을 처리할 수 없습니다." }
            web.post("/checkout/s/$handle/cancel", mapOf("_csrf" to "A".repeat(43)), cookie).status shouldBe 403
            web.post("/checkout/s/$handle/cancel", mapOf("_csrf" to "A".repeat(43))).status shouldBe 403
            db.count("SELECT COUNT(*) FROM checkout_session WHERE handle = ? AND status = 'ACTIVE'", handle) shouldBe 1L
        }

        Then("a manipulated Host header never shapes a redirect or the CSP (origins come from configuration only)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (_, token) = newToken(cred)
            val (status, headers) = web.raw("GET /checkout?token=$token HTTP/1.1", "evil.example.test")
            status shouldContain "303"
            headers.getValue("location") shouldMatch Regex("^/checkout/s/[A-Za-z0-9_-]{22}$")
            headers.values.none { it.contains("evil.example.test") } shouldBe true
        }
    }

    Given("a checkout whose payment was closed by a control-holding path (review #4 m-1, §6.3)") {
        Then("after the expiry job the page is 410 with the fail link (CHECKOUT_EXPIRED); after the payment failed it stays 409 with PAYMENT_NOT_AVAILABLE on every visit") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (paymentId, token) = newToken(cred)
            val (handle, cookie) = opened(token)
            try {
                clock.set(ApiTestTimes.T0.plusSeconds(1800))
                expiry.expireElapsed(com.paycore.payment.payment.application.provided.ExpireElapsedRequestedPaymentsCommand(java.util.UUID.randomUUID().toString(), 100))
                web.get("/checkout/s/$handle", cookie).let {
                    it.status shouldBe 410
                    it.body shouldContain "결제 시간이 지났습니다."
                    it.body shouldContain "https://shop.example.test/fail?paymentId=$paymentId&amp;code=CHECKOUT_EXPIRED"
                }
            } finally {
                clock.reset()
            }

            val (paymentId2, token2) = newToken(cred)
            val (handle2, cookie2) = opened(token2)
            s.change(cred, s.scalar("SELECT merchant_order_id FROM payment WHERE payment_id = ?", paymentId2).toString(), paymentId2) { p, _ ->
                p.startAuthorization(); p.fail(com.paycore.payment.payment.domain.TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED)
            }
            repeat(2) {
                web.get("/checkout/s/$handle2", cookie2).let {
                    it.status shouldBe 409
                    it.body shouldContain "https://shop.example.test/fail?paymentId=$paymentId2&amp;code=PAYMENT_NOT_AVAILABLE"
                }
            }
        }
    }

    Given("amount display uses PayCore's currency units from the entitlement projection (review #3 n-5)") {
        Then("USD 1050 minor units shows 10.50; when the unit cannot be determined the page is 503 (no guessed decimals)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (handle, cookie) = opened(newToken(cred, amount = 1050, currency = "USD").second)
            web.get("/checkout/s/$handle", cookie).let { it.status shouldBe 200; it.body shouldContain "10.50" }
            val order = com.paycore.payment.payment.domain.OrderKey(Environment.TEST, com.paycore.payment.payment.domain.MerchantAccountId(cred.mid), com.paycore.payment.payment.domain.MerchantOrderId("any"))
            eligibility.respondFor(order) { com.paycore.payment.payment.application.required.EligibilityResult.Unavailable(com.paycore.payment.payment.application.provided.EligibilityUnavailableReason.SNAPSHOT_EXPIRED) }
            web.get("/checkout/s/$handle", cookie).let { it.status shouldBe 503; it.body shouldContain "일시적인 오류입니다."; it.body shouldNotContain "SNAPSHOT_EXPIRED" }
        }
    }

    Given("a checkout session past its expiry (read path, jOOQ view — stored UTC wall clock, ARCHITECTURE-CONVENTIONS §9.1)") {
        Then("the page answers 401 one millisecond after the session expiry and 200 one millisecond before (no time-zone shift)") {
            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (_, token) = newToken(cred)
            val (handle, cookie) = opened(token)
            try {
                clock.set(ApiTestTimes.T0.plusSeconds(1200).minusMillis(1)) // 결제창 세션 만료 = T0 + 세션 수명 20분(결제창 30분 안)
                web.get("/checkout/s/$handle", cookie).status shouldBe 200
                clock.set(ApiTestTimes.T0.plusSeconds(1200))
                web.get("/checkout/s/$handle", cookie).status shouldBe 401
            } finally {
                clock.reset()
            }
        }
    }

    Given("three connectors (AN-15, D-AUTHN-006)") {
        Then("/checkout/** answers only on the checkout port and the checkout port answers only /checkout/**; a checkout cookie cannot authenticate /v1/**") {
            val internalWeb = CheckoutClient(internalConnector.localPort)
            val publicWeb = CheckoutClient(publicPort)
            publicWeb.get("/checkout?token=x").status shouldBe 404
            publicWeb.get("/checkout/assets/checkout.css").status shouldBe 404
            internalWeb.get("/checkout?token=x").status shouldBe 404
            web.get("/v1/payments").status shouldBe 404
            web.get("/internal/entitlements").status shouldBe 404
            web.get("/actuator/health").status shouldBe 404
            web.get("/checkout/assets/checkout.css").let { it.status shouldBe 200; it.header("Content-Type")!! shouldStartWith "text/css" }

            val cred = TestCredentials.issue(db, environment = Environment.TEST)
            val (_, cookie) = opened(newToken(cred).second)
            val asCookie = api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), null, s.newKey(),
                headers = mapOf("Content-Type" to "application/json", "Cookie" to cookie))
            asCookie.status shouldBe 401
        }
    }
})

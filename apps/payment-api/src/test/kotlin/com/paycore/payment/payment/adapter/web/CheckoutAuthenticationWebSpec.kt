package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.application.ScriptedCardAuthenticationPort
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.LookupFailure
import com.paycore.payment.payment.domain.ProviderOutcome
import com.paycore.payment.payment.domain.ProviderResultLookup
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager

/**
 * 결제창 인증 흐름 HTTP(AUTHN-001 §6.1·§6.3·§12.2·§12.3, 판정 m-D) — 실서버·실 MySQL, 인증기관은 시험 대역. 화면의 CSRF 숨김 필드(쿠키 비밀에서 파생, 마스킹)로 POST가 통과하고,
 * 성공 복귀는 303 successUrl이며 쿠키를 지우지 않아 같은 복귀 URL 재요청이 같은 303(DUPLICATE)이다. 실패는 200 선택 화면(다시 시도·failUrl), 확인 불가는 503 + Retry-After,
 * 카드사 불허는 422 같은 화면, 취소는 303 failUrl + 쿠키 삭제.
 */
@PaymentApiTest
class CheckoutAuthenticationWebSpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val checkoutConnector: CheckoutConnector,
    private val institution: ScriptedCardAuthenticationPort,
    payments: JpaPaymentRepository,
    controls: JpaOrderPaymentControlRepository,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val web = CheckoutClient(checkoutConnector.localPort)
    val s = ApiSpecSupport(db, payments, controls, transactionManager)

    data class Opened(val paymentId: String, val handle: String, val cookie: String)

    fun opened(): Opened {
        val cred = TestCredentials.issue(db, environment = Environment.TEST)
        val created = api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = s.newOrderId()), cred.key, s.newKey())
        val paymentId = s.json(created.body).get("paymentId").stringValue()
        val issued = api.post("/v1/payments/$paymentId/checkout", "{\"successUrl\":\"https://shop.example.test/ok\",\"failUrl\":\"https://shop.example.test/fail\"}", cred.key)
        val token = s.json(issued.body).get("checkoutUrl").stringValue().substringAfter("token=")
        val r = web.get("/checkout?token=$token")
        return Opened(paymentId, r.header("Location")!!.removePrefix("/checkout/s/"), CheckoutClient.cookiePair(r.header("Set-Cookie")!!))
    }

    fun csrf(o: Opened): String {
        val page = web.get("/checkout/s/${o.handle}", o.cookie)
        page.status shouldBe 200
        return Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(page.body)!!.groupValues[1]
    }

    fun startAuthentication(o: Opened, cardCompany: String = "SIMISSA") =
        web.post("/checkout/s/${o.handle}/authentication", mapOf("cardCompany" to cardCompany, "plan" to "LUMP_SUM", "_csrf" to csrf(o)), o.cookie)

    /** 등록된 세션의 기관 결과를 조회 대역에 두고 복귀 경로를 돌려준다. */
    fun returnPath(o: Opened, outcome: ProviderOutcome? = ProviderOutcome.AUTHENTICATED): String {
        val reg = institution.registrations.last { it.binding.paymentId.value == o.paymentId }
        val providerRef = s.scalar("SELECT provider_transaction_ref FROM authentication_session WHERE payment_id = ? AND status = 'IN_PROGRESS'", o.paymentId) as String
        val resultRef = ScriptedCardAuthenticationPort.newResultRef()
        institution.lookupFor(resultRef) {
            outcome?.let { ProviderResultLookup.Found(institution.authenticated(reg, providerRef, outcome = it)) } ?: ProviderResultLookup.Unverifiable(LookupFailure.SERVER_ERROR)
        }
        return "/checkout/s/${o.handle}/authentication/return?resultRef=$resultRef&state=${reg.state}"
    }

    Given("an open checkout in the browser") {
        Then("POST with the page's CSRF field redirects (303) to the Simulator customer URL; the return redirects to successUrl and keeps the cookie, so a repeat is the same 303") {
            val o = opened()
            val started = startAuthentication(o)
            started.status shouldBe 303
            started.header("Location")!! shouldMatch Regex("^https://sim\\.example\\.test/sim/authenticate/sat_[A-Za-z0-9_-]{22}$")
            val path = returnPath(o)
            val back = web.get(path, o.cookie)
            back.status shouldBe 303
            back.header("Location")!! shouldStartWith "https://shop.example.test/ok?paymentId=${o.paymentId}&authenticationRef=aref_"
            back.header("Set-Cookie") shouldBe null
            web.get(path, o.cookie).let { it.status shouldBe 303; it.header("Location") shouldBe back.header("Location") }
            web.get(path).status shouldBe 401
        }

        Then("an institution failure is a 200 choice page (retry in the same checkout, or the fail URL); an unverifiable lookup is 503 with Retry-After and a retry link") {
            val o = opened()
            startAuthentication(o)
            val failed = web.get(returnPath(o, ProviderOutcome.FAILED), o.cookie)
            failed.status shouldBe 200
            failed.body shouldContain "인증이 완료되지 않았습니다."
            failed.body shouldContain "href=\"/checkout/s/${o.handle}\""
            failed.body shouldContain "https://shop.example.test/fail?paymentId=${o.paymentId}&amp;code=AUTHENTICATION_FAILED"

            startAuthentication(o).status shouldBe 303
            val checking = web.get(returnPath(o, outcome = null), o.cookie)
            checking.status shouldBe 503
            checking.header("Retry-After") shouldBe "1"
            checking.body shouldContain "인증 결과를 확인 중입니다."
            listOf("Exception", "SERVER_ERROR", "sat_").forEach { checking.body shouldNotContain it }
        }

        Then("a card company outside the allowlist re-renders the page with 422 and a fixed notice; an unknown form field is 400") {
            val o = opened()
            val r = startAuthentication(o, cardCompany = "SIMISSZ")
            r.status shouldBe 422
            r.body shouldContain "선택한 카드사 또는 할부를 사용할 수 없습니다."
            r.body shouldContain "name=\"_csrf\""
            web.post("/checkout/s/${o.handle}/authentication", mapOf("cardCompany" to "SIMISSA", "plan" to "LUMP_SUM", "extra" to "1", "_csrf" to csrf(o)), o.cookie).status shouldBe 400
        }

        Then("cancel redirects (303) to the fail URL with USER_CANCELLED and deletes the handle cookie") {
            val o = opened()
            val r = web.post("/checkout/s/${o.handle}/cancel", mapOf("_csrf" to csrf(o)), o.cookie)
            r.status shouldBe 303
            r.header("Location") shouldBe "https://shop.example.test/fail?paymentId=${o.paymentId}&code=USER_CANCELLED"
            r.header("Set-Cookie")!! shouldContain "Max-Age=0"
            web.get("/checkout/s/${o.handle}", o.cookie).status shouldBe 401
        }
    }
})

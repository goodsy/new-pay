package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.application.ScriptedCardAuthenticationPort
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.LookupFailure
import com.paycore.payment.payment.domain.ProviderOutcome
import com.paycore.payment.payment.domain.ProviderResultLookup

/**
 * 결제창 HTTP 흐름 시험 도구(src/test 전용): 가맹점 API로 Payment·토큰을 만들고 checkout 포트에서 교환·화면·인증 시작·복귀·취소를 한다. 비밀 값(토큰·쿠키·CSRF·state·결과 참조)을
 * 모아 두어 로그·화면 누출 검사([secrets])가 바이트로 찾게 한다. 인증기관은 시험 대역.
 */
class CheckoutBrowser(
    private val api: ApiClient,
    val web: CheckoutClient,
    private val s: ApiSpecSupport,
    private val db: DbAccess,
    private val institution: ScriptedCardAuthenticationPort,
) {
    data class Opened(val paymentId: String, val handle: String, val cookie: String, val cred: TestCredentials.Issued, val orderId: String)

    /** 이 흐름에서 나온 비밀 원문(누출 검사 대상). */
    val secrets = mutableListOf<String>()

    fun opened(orderName: String = "Demo order", cred: TestCredentials.Issued = TestCredentials.issue(db, environment = Environment.TEST)): Opened {
        val orderId = s.newOrderId()
        val created = api.post("/v1/payments", CreatePaymentRequests.valid(mid = cred.mid, orderId = orderId, orderName = orderName), cred.key, s.newKey())
        check(created.status == 201) { "payment creation failed: ${created.status}" }
        val paymentId = s.json(created.body).get("paymentId").stringValue()
        val issued = api.post("/v1/payments/$paymentId/checkout", "{\"successUrl\":\"https://shop.example.test/ok\",\"failUrl\":\"https://shop.example.test/fail\"}", cred.key)
        check(issued.status == 201) { "token issuance failed: ${issued.status}" }
        val token = s.json(issued.body).get("checkoutUrl").stringValue().substringAfter("token=")
        secrets += token
        val r = web.get("/checkout?token=$token")
        check(r.status == 303) { "exchange failed: ${r.status}" }
        val cookie = CheckoutClient.cookiePair(r.header("Set-Cookie")!!)
        secrets += cookie.substringAfter('=')
        return Opened(paymentId, r.header("Location")!!.removePrefix("/checkout/s/"), cookie, cred, orderId)
    }

    /** 화면의 CSRF 숨김 필드 값(마스킹된 값 — 그대로 비밀로 취급). [cookie]는 보낼 Cookie 헤더(여러 결제창 쿠키를 함께 보낼 수 있다). */
    fun csrf(o: Opened, cookie: String = o.cookie): String {
        val page = web.get("/checkout/s/${o.handle}", cookie)
        check(page.status == 200) { "page failed: ${page.status}" }
        return Regex("name=\"_csrf\" value=\"([^\"]+)\"").find(page.body)!!.groupValues[1].also { secrets += it }
    }

    fun start(o: Opened, cardCompany: String = "SIMISSA", cookie: String = o.cookie) =
        web.post("/checkout/s/${o.handle}/authentication", mapOf("cardCompany" to cardCompany, "plan" to "LUMP_SUM", "_csrf" to csrf(o, cookie)), cookie)

    fun cancel(o: Opened, cookie: String = o.cookie) = web.post("/checkout/s/${o.handle}/cancel", mapOf("_csrf" to csrf(o, cookie)), cookie)

    /** 등록된 세션의 기관 결과를 조회 대역에 두고 복귀 경로를 돌려준다([outcome] null = 확인 불가). */
    fun returnPath(o: Opened, outcome: ProviderOutcome? = ProviderOutcome.AUTHENTICATED): String {
        val reg = institution.registrations.last { it.binding.paymentId.value == o.paymentId }
        val providerRef = s.scalar("SELECT provider_transaction_ref FROM authentication_session WHERE payment_id = ? AND status = 'IN_PROGRESS'", o.paymentId) as String
        val resultRef = ScriptedCardAuthenticationPort.newResultRef()
        val result = outcome?.let { institution.authenticated(reg, providerRef, outcome = it) }
        result?.authenticated?.let { secrets += it.evidenceRef }
        institution.lookupFor(resultRef) { result?.let { ProviderResultLookup.Found(it) } ?: ProviderResultLookup.Unverifiable(LookupFailure.SERVER_ERROR) }
        secrets += resultRef
        secrets += reg.state
        return "/checkout/s/${o.handle}/authentication/return?resultRef=$resultRef&state=${reg.state}"
    }
}

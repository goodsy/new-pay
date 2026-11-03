package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.CheckoutChoice
import com.paycore.payment.payment.application.provided.CheckoutPageView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.web.csrf.DefaultCsrfToken

/**
 * 결제창 React shell(CHECKOUT-REACT-001 §2·§3·§4): 서버가 Vite index.html의 `#root` 하나에 화면 데이터를 HTML 속성 escape된 JSON으로 넣고, shell 응답만
 * `script-src 'self'` CSP를 쓴다. 데이터는 Thymeleaf 모델이 이미 노출하던 값뿐이다(handle·form-action origin 없음).
 */
class CheckoutShellSpec : FunSpec({

    val template = "<!doctype html><html><head><script type=\"module\" crossorigin src=\"/checkout/assets/checkout.js\"></script>" +
        "<link rel=\"stylesheet\" crossorigin href=\"/checkout/assets/checkout.css\"></head><body><div id=\"root\"></div></body></html>"
    val shell = CheckoutShell(template)
    val handle = "AbCdEfGhIjKlMnOpQrStUv"
    val view = CheckoutPageView(
        handle = handle,
        orderName = "<script>alert('x')</script>&\"주문\"",
        amountDisplay = "10,000",
        currency = "KRW",
        siteHost = "shop.example.test",
        cardCompanies = listOf(CheckoutChoice("SIMISSA", "가상카드A")),
        plans = listOf(CheckoutChoice("LUMP_SUM", "일시불"), CheckoutChoice("INSTALLMENT_3", "3개월 할부")),
        formActionOrigins = listOf("https://sim.example.test", "https://shop.example.test"),
    )
    val csrf = DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "masked-token")

    fun rendered(v: org.springframework.web.servlet.View): MockHttpServletResponse =
        MockHttpServletResponse().also { v.render(emptyMap<String, Any>(), MockHttpServletRequest(), it) }

    test("page data carries exactly the former Thymeleaf model values, escaped inside the attribute, with the shell CSP") {
        val response = rendered(shell.page(view, notice = null, authenticationAction = "/checkout/s/$handle/authentication", cancelAction = "/checkout/s/$handle/cancel", csrf = csrf))
        val body = response.contentAsString
        response.contentType!! shouldStartWith "text/html"
        response.characterEncoding shouldBe "UTF-8"
        response.getHeader("Content-Security-Policy") shouldBe
            "default-src 'none'; style-src 'self'; img-src 'self'; script-src 'self'; base-uri 'none'; frame-ancestors 'none'; " +
            "form-action 'self' https://sim.example.test https://shop.example.test"
        body shouldNotContain "<script>alert"
        body shouldContain "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;\\&quot;주문\\&quot;"
        val data = CheckoutShellPage.data(body)
        data.propertyNames().asSequence().toList() shouldContainExactly listOf(
            "screen", "orderName", "amountDisplay", "currency", "siteHost", "cardCompanies", "plans", "notice",
            "authenticationAction", "cancelAction", "csrfParameter", "csrfToken",
        )
        data.get("screen").stringValue() shouldBe "page"
        data.get("orderName").stringValue() shouldBe view.orderName
        data.get("cardCompanies").get(0).get("label").stringValue() shouldBe "가상카드A"
        data.get("plans").size() shouldBe 2
        data.get("notice").isNull shouldBe true
        data.get("authenticationAction").stringValue() shouldBe "/checkout/s/$handle/authentication"
        data.get("csrfToken").stringValue() shouldBe "masked-token"
        // 단 하나의 #root만 바뀌고 shell의 나머지(외부 module script·stylesheet)는 그대로다.
        CheckoutShellPage.scripts(body) shouldContainExactly listOf("<script type=\"module\" crossorigin src=\"/checkout/assets/checkout.js\"></script>")
    }

    test("error data is the fixed message and server-built links only; the CSP form-action uses the given (configured) origins") {
        val response = rendered(shell.error(CheckoutPages.CHECKOUT_TIME_OVER, retryUrl = null, failRedirect = "https://shop.example.test/fail?paymentId=p&code=CHECKOUT_EXPIRED", formActionOrigins = listOf("https://sim.example.test")))
        val body = response.contentAsString
        body shouldContain "https://shop.example.test/fail?paymentId=p&amp;code=CHECKOUT_EXPIRED"
        val data = CheckoutShellPage.data(body)
        data.propertyNames().asSequence().toList() shouldContainExactly listOf("screen", "message", "retryUrl", "failRedirect")
        data.get("message").stringValue() shouldBe "결제 시간이 지났습니다."
        data.get("retryUrl").isNull shouldBe true
        response.getHeader("Content-Security-Policy")!! shouldContain "script-src 'self'; "
        response.getHeader("Content-Security-Policy")!! shouldContain "form-action 'self' https://sim.example.test"
    }

    test("a shell without exactly one empty #root, or with an inline script, is rejected at load (fail closed)") {
        listOf(
            "<html><body></body></html>",
            "<html><body><div id=\"root\"></div><div id=\"root\"></div></body></html>",
            "<html><head><script>alert(1)</script></head><body><div id=\"root\"></div></body></html>",
            "<html><head><script type=\"module\" src=\"https://cdn.example.test/x.js\"></script></head><body><div id=\"root\"></div></body></html>",
        ).forEach { bad -> shouldThrow<IllegalStateException> { CheckoutShell(bad) } }
    }

    test("the production shell (Vite build on the classpath) loads") {
        val loaded = CheckoutShell()
        val body = rendered(loaded.error(CheckoutPages.TEMPORARY_ERROR, null, null, listOf("https://sim.example.test"))).contentAsString
        CheckoutShellPage.data(body).get("message").stringValue() shouldBe "일시적인 오류입니다."
    }
})

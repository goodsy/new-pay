package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.CheckoutPageView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Component
import org.springframework.web.servlet.View
import java.nio.charset.StandardCharsets

/**
 * 결제창 React shell(CHECKOUT-REACT-001 §2·§3, D-FE-001 같은 출처 정적 번들). Vite가 만든 `index.html`(classpath `checkout-shell/index.html` — 정적 서빙 밖)의
 * `<div id="root"></div>` 하나에 화면 데이터를 `data-checkout` 속성(JSON → HTML 속성 escape)으로 넣어 응답한다. React 번들은 이 속성만 읽는다(fetch 없음).
 * - 데이터는 Thymeleaf 모델이 노출하던 값뿐이다(§3 — handle·form-action origin·내부 사유 없음). 가맹점 입력(주문명)은 JSON 문자열 + 속성 escape, 화면은 텍스트로만 그린다.
 * - shell 응답만 `script-src 'self'` CSP([CheckoutResponseHeadersFilter.shellContentSecurityPolicy]), 그 밖 결제창 응답은 필터 기본값(`script-src 'none'`) 그대로.
 * - 적재 시 검사(fail closed): `#root` 자리가 정확히 하나, script는 같은 출처 `/checkout/assets/` 외부 파일만(본문 없음). 상세 정적 검사는 시험(CheckoutShellScanSpec).
 */
@Component
class CheckoutShell(template: String) {

    @Autowired
    constructor() : this(ClassPathResource(LOCATION).inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) })

    private val before: String
    private val after: String

    init {
        check(template.split(ROOT).size == 2) { "checkout shell must contain exactly one empty #root" }
        SCRIPT.findAll(template).forEach { script ->
            check(SAME_ORIGIN_SCRIPT.matches(script.value)) { "checkout shell scripts must be same-origin external files without a body" }
        }
        before = template.substringBefore(ROOT)
        after = template.substringAfter(ROOT)
    }

    /** 결제창 화면(200·422). CSRF 값은 Spring Security 요청 속성(마스킹된 값) — Thymeleaf `th:action` 숨김 필드와 같은 값이다. */
    fun page(view: CheckoutPageView, notice: String?, authenticationAction: String, cancelAction: String, csrf: CsrfToken): View = ShellView(
        render(
            linkedMapOf(
                "screen" to "page",
                "orderName" to view.orderName,
                "amountDisplay" to view.amountDisplay,
                "currency" to view.currency,
                "siteHost" to view.siteHost,
                "cardCompanies" to view.cardCompanies.map { linkedMapOf("value" to it.value, "label" to it.label) },
                "plans" to view.plans.map { linkedMapOf("value" to it.value, "label" to it.label) },
                "notice" to notice,
                "authenticationAction" to authenticationAction,
                "cancelAction" to cancelAction,
                "csrfParameter" to csrf.parameterName,
                "csrfToken" to csrf.token,
            ),
        ),
        CheckoutResponseHeadersFilter.shellContentSecurityPolicy(view.formActionOrigins),
    )

    /** 오류·결과 화면(AUTHN-001 §6.3): 고정 문구와 서버가 만든 링크만. [formActionOrigins]는 설정 기본값(필터와 같은 값). */
    fun error(message: String, retryUrl: String?, failRedirect: String?, formActionOrigins: List<String>): View = ShellView(
        render(linkedMapOf("screen" to "error", "message" to message, "retryUrl" to retryUrl, "failRedirect" to failRedirect)),
        CheckoutResponseHeadersFilter.shellContentSecurityPolicy(formActionOrigins),
    )

    private fun render(data: Map<String, Any?>): ByteArray =
        (before + "<div id=\"root\" data-checkout=\"" + escapeAttribute(ApiResponses.mapper.writeValueAsString(data)) + "\"></div>" + after)
            .toByteArray(StandardCharsets.UTF_8)

    /** 응답 상태는 [org.springframework.web.servlet.ModelAndView]가 먼저 정한다. 나머지 보안 헤더는 [CheckoutResponseHeadersFilter]가 이미 썼다. */
    private class ShellView(private val body: ByteArray, private val csp: String) : View {
        override fun getContentType(): String = CONTENT_TYPE

        override fun render(model: Map<String, *>?, request: HttpServletRequest, response: HttpServletResponse) {
            response.setHeader(CheckoutResponseHeadersFilter.CSP, csp)
            response.contentType = CONTENT_TYPE
            response.setContentLength(body.size)
            response.outputStream.write(body)
        }
    }

    companion object {
        const val LOCATION = "checkout-shell/index.html"
        private const val ROOT = "<div id=\"root\"></div>"
        private val CONTENT_TYPE = MediaType("text", "html", StandardCharsets.UTF_8).toString()
        private val SCRIPT = Regex("<script\\b[^>]*>[\\s\\S]*?</script>|<script\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val SAME_ORIGIN_SCRIPT = Regex("<script type=\"module\"(?: crossorigin)? src=\"/checkout/assets/[A-Za-z0-9._-]+\\.js\"></script>")

        /** HTML 속성 값 escape(`"`로 감싼 속성 — `&`·`<`·`>`·`"`·`'`). JSON 안의 가맹점 입력이 속성·태그를 끝낼 수 없다. */
        fun escapeAttribute(value: String): String = buildString(value.length + 16) {
            value.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(c)
                }
            }
        }
    }
}

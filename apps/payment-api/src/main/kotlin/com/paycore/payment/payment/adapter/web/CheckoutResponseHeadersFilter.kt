package com.paycore.payment.payment.adapter.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 결제창 응답 헤더(AUTHN-001 §12.4 — checkout 포트 모든 응답). 체인 맨 앞에서 미리 쓴다(오류·CSRF 거절·정적 자원 응답에도 붙는다). CSP `form-action`은 기본으로 설정 Simulator
 * 고객 origin만, 결제창 화면은 그 세션의 Site origin을 더한다(AN-18 — 요청 헤더로 만들지 않는다, m-9).
 * 스크립트(CHECKOUT-REACT-001 §4): 기본은 `script-src 'none'`. React shell HTML 응답만 [CheckoutShell]이 [shellContentSecurityPolicy]로 덮어써 같은 출처 번들
 * (`script-src 'self'`)을 허용한다 — 그 밖 지시어는 같고 `connect-src`·`'unsafe-inline'`·외부 origin은 없다.
 */
class CheckoutResponseHeadersFilter(private val defaultFormActionOrigins: () -> List<String>) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader(CSP, contentSecurityPolicy(defaultFormActionOrigins()))
        STATIC.forEach { (name, value) -> response.setHeader(name, value) }
        chain.doFilter(request, response)
    }

    companion object {
        const val CSP = "Content-Security-Policy"
        private val STATIC = listOf(
            "X-Frame-Options" to "DENY",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cache-Control" to "no-store",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), payment=()",
        )

        /** shell이 아닌 결제창 응답(redirect·정적 자원·필터 단계): 스크립트 없음. */
        fun contentSecurityPolicy(formActionOrigins: List<String>): String = policy("'none'", formActionOrigins)

        /** React shell HTML 응답: 같은 출처 번들(`/checkout/assets/`)만. */
        fun shellContentSecurityPolicy(formActionOrigins: List<String>): String = policy("'self'", formActionOrigins)

        private fun policy(scriptSource: String, formActionOrigins: List<String>): String =
            "default-src 'none'; style-src 'self'; img-src 'self'; script-src $scriptSource; base-uri 'none'; frame-ancestors 'none'; " +
                "form-action " + (listOf("'self'") + formActionOrigins).joinToString(" ")
    }
}

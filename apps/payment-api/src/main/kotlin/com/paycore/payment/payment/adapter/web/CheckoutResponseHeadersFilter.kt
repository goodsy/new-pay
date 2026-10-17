package com.paycore.payment.payment.adapter.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 결제창 응답 헤더(AUTHN-001 §12.4 — checkout 포트 모든 응답). 체인 맨 앞에서 미리 쓴다(오류·CSRF 거절·정적 자원 응답에도 붙는다). CSP `form-action`은 기본으로 설정 Simulator
 * 고객 origin만, 결제창 화면은 Controller가 그 세션의 Site origin을 더해 [contentSecurityPolicy]로 덮어쓴다(AN-18 — 요청 헤더로 만들지 않는다, m-9). JS 없음(`script-src 'none'`).
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

        fun contentSecurityPolicy(formActionOrigins: List<String>): String =
            "default-src 'none'; style-src 'self'; img-src 'self'; script-src 'none'; base-uri 'none'; frame-ancestors 'none'; " +
                "form-action " + (listOf("'self'") + formActionOrigins).joinToString(" ")
    }
}

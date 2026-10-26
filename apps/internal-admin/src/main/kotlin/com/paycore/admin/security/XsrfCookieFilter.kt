package com.paycore.admin.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.filter.OncePerRequestFilter
import java.util.function.Supplier

/**
 * 세션에 저장된 CSRF 토큰을 SPA가 읽을 수 있는 쿠키로 **전달만** 한다(D-10). 쿠키 값은 비교 기준이 아니다 — 서버는 헤더 값을 세션 토큰과
 * 비교하므로 공격자가 쿠키와 헤더를 같은 값으로 맞춰도 통과하지 못한다.
 *
 * - 인증된 `/api/` 요청에서만 쓴다(익명·정적 요청은 토큰을 꺼내지 않으므로 세션을 만들지 않는다 — n-1). 세션이 없으면 토큰은 저장되지 않으므로
 *   그 쿠키 값으로는 상태 변경 요청이 통과하지 않는다. 로그인 전 토큰 발급(`GET /api/auth/csrf`)과 로그인 단계의 토큰 재발급은 웹 adapter
 *   (`AuthController`)가 같은 이름·속성의 쿠키로 직접 쓴다(S4).
 * - `__Host-` 접두: Secure·Path=/·Domain 없음(브라우저가 강제). JS가 읽어야 하므로 HttpOnly는 아니다. SameSite=Strict.
 * - Spring bean이 아니다(서블릿 필터로 자동 등록되지 않게 SecurityConfig가 체인에만 넣는다).
 */
class XsrfCookieFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication != null && authentication.isAuthenticated && authentication !is AnonymousAuthenticationToken) {
            @Suppress("UNCHECKED_CAST")
            val token = (request.getAttribute(SpaCsrfTokenRequestHandler.RAW_TOKEN_ATTRIBUTE) as? Supplier<CsrfToken>)?.get()
            if (token != null) {
                val cookie = ResponseCookie.from(XSRF_COOKIE, token.token)
                    .path("/").secure(true).httpOnly(false).sameSite("Strict").build()
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
            }
        }
        filterChain.doFilter(request, response)
    }

    companion object {
        const val XSRF_COOKIE = "__Host-PCADMIN-XSRF"
    }
}

package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminSessionState
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.stereotype.Component

/**
 * 로그인 세션 기록(§4.3.2·§4.4 — 세션 고정 방지). 일반 2단계 로그인([AuthController])과 데모 간편 로그인([DemoQuickLoginController] — ADMIN-DEMO-LOGIN-001 §4)이
 * **같은 경로**를 쓴다: 세션 ID 재발급 → 세션 문자열 상태 기록 → CSRF 토큰 재발급(쿠키 전달). 동작은 AuthController에서 옮겨 온 그대로다.
 */
@Component
class LoginSessionWriter(private val csrfTokens: CsrfTokenRepository) {

    /** 세션 ID 재발급 → 로그인 상태 기록 → CSRF 토큰 재발급(쿠키 전달). */
    fun renew(request: HttpServletRequest, response: HttpServletResponse, state: AdminSessionState): HttpSession {
        request.changeSessionId()
        val session = request.getSession(false)!!
        state.toAttributes().forEach { (name, value) -> if (value == null) session.removeAttribute(name) else session.setAttribute(name, value) }
        val token = csrfTokens.generateToken(request)
        csrfTokens.saveToken(token, request, response)
        writeXsrfCookie(response, token.token)
        return session
    }

    fun writeXsrfCookie(response: HttpServletResponse, token: String) {
        // 이름·속성은 security.XsrfCookieFilter와 같다(`__Host-` — Secure·Path=/·Domain 없음, JS가 읽어야 하므로 HttpOnly 아님). LoginHttpSpec이 고정.
        val cookie = ResponseCookie.from(XSRF_COOKIE, token).path("/").secure(true).httpOnly(false).sameSite("Strict").build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    private companion object {
        const val XSRF_COOKIE = "__Host-PCADMIN-XSRF"
    }
}

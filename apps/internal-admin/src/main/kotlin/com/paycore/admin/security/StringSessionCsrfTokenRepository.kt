package com.paycore.admin.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.csrf.DefaultCsrfToken
import java.security.SecureRandom
import java.util.Base64

/**
 * 세션 저장 동기화 CSRF 토큰(D-10) — 세션에는 **토큰 문자열만** 저장한다(세션 값은 문자열만 — §17.5; Security 기본 저장소는 `DefaultCsrfToken` 객체를 저장).
 * 세션이 **이미 있을 때만** 저장한다(S1 리뷰 M1 — 익명 상태 변경 요청이 세션을 만들지 않게). 로그인 전 토큰은 `GET /api/auth/csrf`가 세션을 명시적으로 만든 뒤 발급한다.
 */
class StringSessionCsrfTokenRepository : CsrfTokenRepository {
    private val random = SecureRandom()

    override fun generateToken(request: HttpServletRequest): CsrfToken =
        DefaultCsrfToken(HEADER, PARAMETER, Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes)))

    override fun saveToken(token: CsrfToken?, request: HttpServletRequest, response: HttpServletResponse) {
        val session = request.getSession(false) ?: return
        if (token == null) session.removeAttribute(ATTRIBUTE) else session.setAttribute(ATTRIBUTE, token.token)
    }

    override fun loadToken(request: HttpServletRequest): CsrfToken? =
        (request.getSession(false)?.getAttribute(ATTRIBUTE) as? String)?.let { DefaultCsrfToken(HEADER, PARAMETER, it) }

    companion object {
        const val HEADER = SecurityConfig.XSRF_HEADER
        const val PARAMETER = "_csrf"
        const val ATTRIBUTE = "paycore.admin.csrf"
        private const val TOKEN_BYTES = 32
    }
}

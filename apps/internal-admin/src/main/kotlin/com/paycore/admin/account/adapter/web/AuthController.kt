package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminLoginUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.account.application.provided.PasswordStepResult
import com.paycore.admin.account.application.provided.PasswordVerified
import com.paycore.admin.account.application.provided.TotpStepResult
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 인증 HTTP(§4.3.2·§4.4). 세션에는 [AdminSessionState]의 **문자열 값만** 쓴다. 로그인 각 단계 성공마다 세션 ID와 CSRF 토큰을 재발급한다(세션 고정 방지).
 * - `GET /api/auth/csrf`: 세션을 **명시적으로** 만든 뒤(익명 idle — Demo 10분) 토큰을 발급한다. 원문 토큰은 `Set-Cookie`로만(본문 없음 — BREACH 노출면 없음), 있으면 재사용.
 * - `POST /api/auth/password`·`/api/auth/totp`: 실패는 원인 비구분 401 `INVALID_CREDENTIALS`.
 * - `POST /api/auth/logout`, `GET /api/auth/me`(계정 현재값 — 역할은 세션이 아니라 현재 행).
 * 원격 주소는 `remoteAddr`만(§4.8.1), 상관 ID는 요청마다 서버가 만든다.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val login: AdminLoginUseCase,
    private val sessions: AdminSessionQuery,
    private val csrfTokens: CsrfTokenRepository,
) {

    @GetMapping("/csrf")
    fun csrf(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        if (request.getSession(false) == null) {
            request.getSession(true).maxInactiveInterval = sessions.policy().anonymousIdle.seconds.toInt()
        }
        val token = csrfTokens.loadToken(request) ?: csrfTokens.generateToken(request).also { csrfTokens.saveToken(it, request, response) }
        writeXsrfCookie(response, token.token)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @PostMapping("/password")
    fun password(@RequestBody body: PasswordRequest, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Map<String, String>> {
        val session = request.getSession(false) ?: return invalidCredentials()
        val password = body.password.orEmpty().toCharArray()
        val outcome = try {
            login.verifyPassword(body.loginName.orEmpty(), password, context(request))
        } finally {
            password.fill('\u0000')
        }
        return when (val result = outcome) {
            PasswordStepResult.InvalidCredentials -> {
                clearState(session)
                invalidCredentials()
            }
            is PasswordStepResult.Verified -> {
                val renewed = renew(request, response, AdminSessionState(
                    AdminSessionState.Stage.PASSWORD_VERIFIED, result.verified.actorId, result.verified.securityVersion,
                    stepExpiresAt = result.verified.stepExpiresAt,
                ))
                renewed.maxInactiveInterval = result.stepIdle.seconds.toInt()
                ok(linkedMapOf("next" to "TOTP"))
            }
        }
    }

    @PostMapping("/totp")
    fun totp(@RequestBody body: TotpRequest, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<out Any> {
        val session = request.getSession(false) ?: return invalidCredentials()
        val state = AdminSessionState.read(session::getAttribute)
        if (state == null || state.stage != AdminSessionState.Stage.PASSWORD_VERIFIED) return invalidCredentials()
        val verified = PasswordVerified(state.actorId, state.securityVersion, state.stepExpiresAt!!)
        return when (val result = login.verifyTotp(verified, body.code.orEmpty(), context(request))) {
            is TotpStepResult.Rejected -> {
                if (result.endSession) session.invalidate()
                invalidCredentials()
            }
            is TotpStepResult.Authenticated -> {
                val renewed = renew(request, response, AdminSessionState(
                    AdminSessionState.Stage.AUTHENTICATED, result.actorId, result.securityVersion, result.sessionGeneration, result.authenticatedAt,
                ))
                renewed.maxInactiveInterval = result.idle.seconds.toInt()
                me(result.actorId)
            }
        }
    }

    /**
     * 로그아웃은 안전 방향(S4 리뷰 m-1 — D-78과 같은 종류): 세션 폐기는 finally에서 **항상** 한다. 감사 기록이 실패하면 예외가 전파되어 공통 처리가
     * 로그 분류 + 500 `INTERNAL_ERROR`로 응답한다(세션은 이미 폐기).
     */
    @PostMapping("/logout")
    fun logout(authentication: Authentication, request: HttpServletRequest): ResponseEntity<Void> {
        try {
            login.logout(authentication.name, context(request))
        } finally {
            request.getSession(false)?.invalidate()
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): ResponseEntity<out Any> = me(authentication.name)

    private fun me(actorId: String): ResponseEntity<out Any> {
        val view = sessions.current(actorId) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "UNAUTHENTICATED"))
        return ok(linkedMapOf("actorId" to view.actorId, "displayName" to view.displayName, "roles" to view.roles.sorted()))
    }

    /** 세션 ID 재발급 → 로그인 상태 기록 → CSRF 토큰 재발급(쿠키 전달). */
    private fun renew(request: HttpServletRequest, response: HttpServletResponse, state: AdminSessionState): HttpSession {
        request.changeSessionId()
        val session = request.getSession(false)!!
        state.toAttributes().forEach { (name, value) -> if (value == null) session.removeAttribute(name) else session.setAttribute(name, value) }
        val token = csrfTokens.generateToken(request)
        csrfTokens.saveToken(token, request, response)
        writeXsrfCookie(response, token.token)
        return session
    }

    private fun clearState(session: HttpSession) = AdminSessionState.NAMES.forEach(session::removeAttribute)

    private fun context(request: HttpServletRequest) = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45))

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun invalidCredentials(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).body(mapOf("error" to "INVALID_CREDENTIALS"))

    private fun writeXsrfCookie(response: HttpServletResponse, token: String) {
        // 이름·속성은 security.XsrfCookieFilter와 같다(`__Host-` — Secure·Path=/·Domain 없음, JS가 읽어야 하므로 HttpOnly 아님). LoginHttpSpec이 고정.
        val cookie = ResponseCookie.from(XSRF_COOKIE, token).path("/").secure(true).httpOnly(false).sameSite("Strict").build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
    }

    class PasswordRequest(val loginName: String?, val password: String?) {
        override fun toString(): String = "PasswordRequest(***)"
    }

    class TotpRequest(val code: String?) {
        override fun toString(): String = "TotpRequest(***)"
    }

    private companion object {
        const val XSRF_COOKIE = "__Host-PCADMIN-XSRF"
    }
}

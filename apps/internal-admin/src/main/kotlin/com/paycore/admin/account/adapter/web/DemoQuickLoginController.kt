package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.account.application.provided.DemoQuickLoginResult
import com.paycore.admin.account.application.provided.DemoQuickLoginUseCase
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Conditional
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 데모 간편 로그인 HTTP(ADMIN-DEMO-LOGIN-001 §3 — D-ADMIN-AUTH-DEMO-001, **Demo 전용 임시 기능 — 운영 준비 전 반드시 끔**).
 * [DemoQuickLoginCondition](배치 모드 DEMO **그리고** enabled)일 때만 등록된다 — 아니면 경로가 없어 404.
 * - `GET /api/auth/demo-quick-login`: 버튼 목록(로그인 이름·표시명·역할·사용 가능). 비밀 없음.
 * - `POST /api/auth/demo-quick-login {loginName}`: 세션(`GET /api/auth/csrf`)·CSRF 필요. 성공이면 일반 TOTP 성공과 같은 [LoginSessionWriter.renew]로
 *   세션 ID·CSRF 재발급 + 로그인 idle, 본문은 `/api/auth/me`와 같다. 실패는 원인 비구분 401 `INVALID_CREDENTIALS`.
 */
@RestController
@RequestMapping("/api/auth/demo-quick-login")
@Conditional(DemoQuickLoginCondition::class)
class DemoQuickLoginController(
    private val quick: DemoQuickLoginUseCase,
    private val meBody: AdminMeBody,
    private val writer: LoginSessionWriter,
) {

    @GetMapping
    fun options(): ResponseEntity<Map<String, Any>> = ok(
        linkedMapOf(
            "accounts" to quick.options().map {
                linkedMapOf("loginName" to it.loginName, "displayName" to it.displayName, "roles" to it.roles, "available" to it.available)
            },
        ),
    )

    @PostMapping
    fun login(@RequestBody body: QuickLoginRequest, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<out Any> {
        request.getSession(false) ?: return invalidCredentials()
        val context = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45))
        return when (val result = quick.login(body.loginName.orEmpty(), context)) {
            DemoQuickLoginResult.Rejected -> {
                request.getSession(false)?.let { session -> AdminSessionState.NAMES.forEach(session::removeAttribute) }
                invalidCredentials()
            }
            is DemoQuickLoginResult.Authenticated -> {
                val renewed = writer.renew(request, response, AdminSessionState(
                    AdminSessionState.Stage.AUTHENTICATED, result.actorId, result.securityVersion, result.sessionGeneration, result.authenticatedAt,
                    loginMethod = AdminSessionState.DEMO_QUICK,
                ))
                renewed.maxInactiveInterval = result.idle.seconds.toInt()
                // 리뷰 NIT-c: `/api/auth/me`와 같은 본문·같은 표시 규칙([AdminMeBody] 한 곳).
                val body = meBody.of(result.actorId, renewed)
                    ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).body(mapOf("error" to "UNAUTHENTICATED"))
                ok(body)
            }
        }
    }

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun invalidCredentials(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).body(mapOf("error" to "INVALID_CREDENTIALS"))

    class QuickLoginRequest(val loginName: String?) {
        override fun toString(): String = "QuickLoginRequest(***)"
    }
}

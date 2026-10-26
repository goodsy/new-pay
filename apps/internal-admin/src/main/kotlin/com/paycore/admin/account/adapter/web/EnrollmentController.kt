package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.account.application.provided.EnrollmentDraft
import com.paycore.admin.account.application.provided.EnrollmentFailure
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 등록 코드 흐름 HTTP(§4.3.6 — S7, 미인증 경로). 세션은 `GET /api/auth/csrf`가 만든 익명 세션을 쓴다(없으면 401). 진행 상태는 [EnrollmentSessionState]의 문자열 속성뿐.
 * - `start {code}`: 코드 확인(소비 안 함) → **세션 ID·CSRF 재발급**(로그인 상태가 있었다면 지운다) → `{purpose, needsPassword, needsTotp}`.
 * - `password {password}`: 목적이 비밀번호를 요구할 때만. 정책 위반 400 `PASSWORD_POLICY`.
 * - `totp`: TOTP 시크릿 생성 — `otpauthUri`는 이 응답 1회만(no-store), 시크릿은 암호화해 세션에만.
 * - `complete {totpCode?}`: 완료 TX(코드 원자 소비) → 성공이면 **세션 폐기**(본인은 새로 로그인한다).
 * 실패 code: `ENROLLMENT_CODE_INVALID`·`ENROLLMENT_NOT_APPLICABLE`·`ENROLLMENT_STEP_INVALID`(§4.3.6 n-C), 1213·1205는 409 `RETRY_LATER`([AccountWebErrors]).
 */
@RestController
@RequestMapping("/api/enroll")
class EnrollmentController(private val enrollment: EnrollmentUseCase, private val csrfTokens: CsrfTokenRepository) {

    @PostMapping("/start")
    fun start(@RequestBody body: CodeBody, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<out Any> {
        // 방어 분기(S8 리뷰 nit-3): 세션이 없으면 CSRF filter가 컨트롤러 전에 403으로 거부하므로(§24.8 m-4(b)) 정상 경로로는 도달하지 않는다 — CSRF 설정이 바뀌어도 세션 없이 진행하지 않게 남긴다.
        if (request.getSession(false) == null) return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        return when (val result = enrollment.begin(body.code.orEmpty())) {
            is EnrollmentResult.InProgress -> {
                val session = renew(request, response)
                AdminSessionState.NAMES.forEach(session::removeAttribute)
                store(session, result.draft)
                val purpose = PURPOSES.getValue(result.draft.purpose)
                ok(linkedMapOf("purpose" to result.draft.purpose, "needsPassword" to purpose.first, "needsTotp" to purpose.second))
            }
            is EnrollmentResult.Failed -> failure(result.failure)
            else -> error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        }
    }

    @PostMapping("/password")
    fun password(@RequestBody body: PasswordBody, request: HttpServletRequest): ResponseEntity<out Any> {
        val (session, draft) = current(request) ?: return error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        val password = body.password.orEmpty().toCharArray()
        val result = try {
            enrollment.providePassword(draft, password)
        } finally {
            password.fill('\u0000')
        }
        return when (result) {
            is EnrollmentResult.InProgress -> {
                store(session, result.draft)
                ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            }
            is EnrollmentResult.Failed -> failure(result.failure)
            else -> error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        }
    }

    @PostMapping("/totp")
    fun totp(request: HttpServletRequest): ResponseEntity<out Any> {
        val (session, draft) = current(request) ?: return error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        return when (val result = enrollment.startTotp(draft)) {
            is EnrollmentResult.TotpProvisioning -> {
                store(session, result.draft)
                ok(mapOf("otpauthUri" to result.otpauthUri))
            }
            is EnrollmentResult.Failed -> failure(result.failure)
            else -> error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        }
    }

    @PostMapping("/complete")
    fun complete(@RequestBody(required = false) body: CompleteBody?, request: HttpServletRequest): ResponseEntity<out Any> {
        val (session, draft) = current(request) ?: return error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        val context = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45))
        return when (val result = enrollment.complete(draft, body?.totpCode?.takeIf { it.isNotEmpty() }, context)) {
            is EnrollmentResult.Completed -> {
                session.invalidate()
                ok(mapOf("status" to "COMPLETED"))
            }
            is EnrollmentResult.Failed -> {
                if (result.failure == EnrollmentFailure.CODE_INVALID || result.failure == EnrollmentFailure.NOT_APPLICABLE) clear(session)
                failure(result.failure)
            }
            else -> error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        }
    }

    private fun current(request: HttpServletRequest): Pair<HttpSession, EnrollmentDraft>? {
        val session = request.getSession(false) ?: return null
        val draft = EnrollmentSessionState.read(session::getAttribute)
        if (draft == null) {
            clear(session)
            return null
        }
        return session to draft
    }

    private fun store(session: HttpSession, draft: EnrollmentDraft) =
        EnrollmentSessionState.write(draft) { name, value -> if (value == null) session.removeAttribute(name) else session.setAttribute(name, value) }

    private fun clear(session: HttpSession) = EnrollmentSessionState.NAMES.forEach(session::removeAttribute)

    /** 세션 ID 재발급(고정 방지) + CSRF 토큰 재발급(쿠키 전달 — [AuthController]와 같은 속성). */
    private fun renew(request: HttpServletRequest, response: HttpServletResponse): HttpSession {
        request.changeSessionId()
        val session = request.getSession(false)!!
        val token = csrfTokens.generateToken(request)
        csrfTokens.saveToken(token, request, response)
        val cookie = ResponseCookie.from(XSRF_COOKIE, token.token).path("/").secure(true).httpOnly(false).sameSite("Strict").build()
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
        return session
    }

    private fun failure(failure: EnrollmentFailure): ResponseEntity<Map<String, String>> = when (failure) {
        EnrollmentFailure.CODE_INVALID -> error(HttpStatus.BAD_REQUEST, "ENROLLMENT_CODE_INVALID")
        EnrollmentFailure.NOT_APPLICABLE -> error(HttpStatus.BAD_REQUEST, "ENROLLMENT_NOT_APPLICABLE")
        EnrollmentFailure.STEP_INVALID -> error(HttpStatus.BAD_REQUEST, STEP_INVALID)
        EnrollmentFailure.PASSWORD_TOO_SHORT, EnrollmentFailure.PASSWORD_TOO_LONG -> error(HttpStatus.BAD_REQUEST, "PASSWORD_POLICY")
    }

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun error(status: HttpStatus, code: String): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    class CodeBody(val code: String?) {
        override fun toString(): String = "CodeBody(***)"
    }

    class PasswordBody(val password: String?) {
        override fun toString(): String = "PasswordBody(***)"
    }

    class CompleteBody(val totpCode: String?) {
        override fun toString(): String = "CompleteBody(***)"
    }

    private companion object {
        const val XSRF_COOKIE = "__Host-PCADMIN-XSRF"
        const val STEP_INVALID = "ENROLLMENT_STEP_INVALID"

        /** 목적 → (비밀번호 필요, TOTP 필요) — D-ADMIN1-011 목적별 요소(domain `EnrollmentPurpose`와 같은 표). */
        val PURPOSES = mapOf(
            "ENROLL" to (true to true), "RECOVERY" to (true to true), "PASSWORD_RESET" to (true to false), "TOTP_REENROLL" to (false to true),
        )
    }
}

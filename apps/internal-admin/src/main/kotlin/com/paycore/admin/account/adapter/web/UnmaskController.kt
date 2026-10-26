package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.account.application.provided.UnmaskRequest
import com.paycore.admin.account.application.provided.UnmaskResult
import com.paycore.admin.account.application.provided.UnmaskUseCase
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** `POST /api/unmask`(AC10 틀 — §4.5.3): 권한 없음 403, 사유·TOTP 누락 400, TOTP 실패 403 `STEP_UP_FAILED`, 통과는 204(데이터 공급자 없음). */
@RestController
class UnmaskController(private val unmask: UnmaskUseCase) {

    @PostMapping("/api/unmask")
    fun request(@RequestBody body: Body, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = AdminSessionState.read { request.getSession(false)?.getAttribute(it) }?.sessionGeneration
            ?: return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val result = unmask.request(
            UnmaskRequest(
                authentication.name, generation, body.targetRef.orEmpty(), body.reason.orEmpty(), body.totpCode.orEmpty(),
                AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45)),
            ),
        )
        return when (result) {
            UnmaskResult.NoDataSource -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            is UnmaskResult.Invalid -> error(HttpStatus.BAD_REQUEST, result.code)
            UnmaskResult.Denied -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
            UnmaskResult.SessionReplaced -> {
                request.getSession(false)?.invalidate()
                error(HttpStatus.UNAUTHORIZED, "SESSION_REPLACED")
            }
            UnmaskResult.StepUpFailed -> error(HttpStatus.FORBIDDEN, "STEP_UP_FAILED")
        }
    }

    private fun error(status: HttpStatus, code: String) = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    class Body(val targetRef: String?, val reason: String?, val totpCode: String?) {
        override fun toString(): String = "UnmaskBody(targetRef=$targetRef, ***)"
    }
}

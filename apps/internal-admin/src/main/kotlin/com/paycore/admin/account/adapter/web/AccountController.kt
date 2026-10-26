package com.paycore.admin.account.adapter.web

import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AccountQuery
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.CreateAdminAccount
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 계정 관리 HTTP(S7 — §4.10 계정 목록·1인 명령). 권한 판정은 Use Case(계정 현재 행)가 하고 여기서는 결과를 고정 code로 옮긴다:
 * 성공 200/201, 권한 없음 403 `FORBIDDEN`, 입력 형식 400 `INVALID_INPUT`, 업무 거절 409 `<code>`. 등록 코드 원문은 생성·재발급 응답 본문에만(no-store).
 */
@RestController
@RequestMapping("/api/accounts")
class AccountController(private val commands: AdminAccountCommandUseCase, private val query: AccountQuery) {

    @GetMapping
    fun list(authentication: Authentication): ResponseEntity<out Any> {
        val page = query.list(authentication.name) ?: return error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        return ok(
            linkedMapOf(
                "accounts" to page.accounts.map {
                    linkedMapOf<String, Any?>(
                        "actorId" to it.actorId, "displayName" to it.displayName, "status" to it.status,
                        "activeRoles" to it.activeRoles, "suspendedRoles" to it.suspendedRoles, "pendingPurpose" to it.pendingPurpose,
                    ).also { m -> it.loginName?.let { name -> m["loginName"] = name } }
                },
                "truncated" to page.truncated,
            ),
        )
    }

    /** `ROLE_REACTIVATE` 원인 후보(Q-S7-2). SECURITY_ADMIN만(아니면 403). 원인 ID와 그 원인으로 정지된 역할만 — 계정·승인 세부는 없다. */
    @GetMapping("/{actorId}/reactivation-causes")
    fun reactivationCauses(@PathVariable actorId: String, authentication: Authentication): ResponseEntity<out Any> {
        val causes = query.reactivationCauses(authentication.name, actorId) ?: return error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        return ok(mapOf("causes" to causes.map { linkedMapOf("cause" to it.cause, "roles" to it.roles) }))
    }

    @PostMapping
    fun create(@RequestBody body: CreateBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        respond(commands.createAccount(CreateAdminAccount(authentication.name, body.loginName.orEmpty(), body.displayName.orEmpty(), context(request))), HttpStatus.CREATED)

    @PostMapping("/{actorId}/suspend")
    fun suspend(@PathVariable actorId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        respond(commands.suspend(TargetedAccountCommand(authentication.name, actorId, context(request))), HttpStatus.OK, mapOf("status" to "SUSPENDED"))

    @PostMapping("/{actorId}/enrollment-code")
    fun reissue(@PathVariable actorId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        respond(commands.reissueEnrollCode(TargetedAccountCommand(authentication.name, actorId, context(request))), HttpStatus.OK)

    @PostMapping("/{actorId}/roles/{role}/revoke")
    fun revoke(@PathVariable actorId: String, @PathVariable role: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        respond(commands.revokeRole(RevokeAdminRole(authentication.name, actorId, role, context(request))), HttpStatus.OK, mapOf("status" to "REVOKED"))

    private fun respond(result: AccountCommandResult, success: HttpStatus, fixed: Map<String, String>? = null): ResponseEntity<out Any> = when (result) {
        is AccountCommandResult.Succeeded -> ResponseEntity.status(success).cacheControl(CacheControl.noStore()).body(
            fixed ?: linkedMapOf("actorId" to result.targetActorId).also { m -> result.enrollmentCode?.let { m["enrollmentCode"] = it } },
        )
        AccountCommandResult.Denied -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
        is AccountCommandResult.Rejected -> if (result.code == INVALID_INPUT) error(HttpStatus.BAD_REQUEST, INVALID_INPUT) else error(HttpStatus.CONFLICT, result.code)
    }

    private fun context(request: HttpServletRequest) = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45))

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun error(status: HttpStatus, code: String): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(mapOf("error" to code))

    class CreateBody(val loginName: String?, val displayName: String?)

    private companion object {
        const val INVALID_INPUT = "INVALID_INPUT"
    }
}

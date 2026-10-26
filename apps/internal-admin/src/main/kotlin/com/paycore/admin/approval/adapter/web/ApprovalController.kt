package com.paycore.admin.approval.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.ApprovalView
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.approval.application.provided.WithdrawApproval
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 2인 승인 HTTP(§4.7). 요청 본문은 ID·TOTP 코드·요청 입력뿐이고, 세션 세대는 서버 세션의 문자열 상태에서 읽는다(요청 값 아님). 모든 응답 `no-store`.
 * 재설정 종류의 등록 코드 원문은 **승인 응답 본문에만 1회**(§4.7.7 — 목록·상세·감사·로그에 없음). 결과 code는 고정 JSON.
 */
@RestController
@RequestMapping("/api/approvals")
class ApprovalController(private val approvals: ApprovalUseCase, private val query: ApprovalQuery) {

    @PostMapping
    fun request(@RequestBody body: CreateRequest, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = approvals.request(
            RequestApproval(authentication.name, generation, body.kind.orEmpty(), body.targetActorId.orEmpty(), body.grantedRole, body.causeApprovalId, context(request)),
        )
        return respond(outcome, request)
    }

    @PostMapping("/{approvalId}/approve")
    fun approve(@PathVariable approvalId: String, @RequestBody body: TotpBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        return respond(approvals.approve(DecideApproval(approvalId, authentication.name, generation, body.totpCode.orEmpty(), context(request))), request)
    }

    @PostMapping("/{approvalId}/reject")
    fun reject(@PathVariable approvalId: String, @RequestBody body: TotpBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        return respond(approvals.reject(DecideApproval(approvalId, authentication.name, generation, body.totpCode.orEmpty(), context(request))), request)
    }

    @PostMapping("/{approvalId}/withdraw")
    fun withdraw(@PathVariable approvalId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        return respond(approvals.withdraw(WithdrawApproval(approvalId, authentication.name, generation, context(request))), request)
    }

    @GetMapping
    fun list(@RequestParam(defaultValue = "pending") view: String, authentication: Authentication): ResponseEntity<out Any> {
        val rows = query.list(authentication.name, view != "mine") ?: return status(HttpStatus.FORBIDDEN, "FORBIDDEN")
        return ok(mapOf("approvals" to rows.map(::json)))
    }

    @GetMapping("/{approvalId}")
    fun detail(@PathVariable approvalId: String, authentication: Authentication): ResponseEntity<out Any> {
        val row = query.detail(authentication.name, approvalId) ?: return status(HttpStatus.NOT_FOUND, "NOT_FOUND")
        return ok(json(row))
    }

    private fun respond(outcome: ApprovalOutcome, request: HttpServletRequest): ResponseEntity<out Any> = when (outcome) {
        is ApprovalOutcome.Created -> ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(mapOf("approvalId" to outcome.approvalId))
        is ApprovalOutcome.Executed -> ok(linkedMapOf("approvalId" to outcome.approvalId, "status" to "EXECUTED").also { m -> outcome.enrollmentCode?.let { m["enrollmentCode"] = it } })
        is ApprovalOutcome.Closed -> ok(mapOf("status" to outcome.status))
        is ApprovalOutcome.Invalidated -> status(HttpStatus.CONFLICT, "INVALIDATED", outcome.code)
        is ApprovalOutcome.OwnerRejected -> status(HttpStatus.CONFLICT, "OWNER_REJECTED", outcome.code)
        is ApprovalOutcome.Rejected -> if (outcome.code == INVALID_INPUT) status(HttpStatus.BAD_REQUEST, INVALID_INPUT) else status(HttpStatus.CONFLICT, outcome.code)
        is ApprovalOutcome.Refused -> status(HttpStatus.FORBIDDEN, outcome.code)
        ApprovalOutcome.AlreadyDecided -> status(HttpStatus.CONFLICT, "ALREADY_DECIDED")
        ApprovalOutcome.Expired -> status(HttpStatus.CONFLICT, "EXPIRED")
        ApprovalOutcome.Denied -> status(HttpStatus.FORBIDDEN, "FORBIDDEN")
        ApprovalOutcome.SessionReplaced -> {
            request.getSession(false)?.invalidate()
            status(HttpStatus.UNAUTHORIZED, "SESSION_REPLACED")
        }
        ApprovalOutcome.StepUpFailed -> status(HttpStatus.FORBIDDEN, "STEP_UP_FAILED")
        ApprovalOutcome.NotFound -> status(HttpStatus.NOT_FOUND, "NOT_FOUND")
        ApprovalOutcome.RetryLater -> status(HttpStatus.CONFLICT, "RETRY_LATER")
        ApprovalOutcome.IntegrityViolation -> status(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_INTEGRITY")
    }

    private fun generation(request: HttpServletRequest): Long? =
        AdminSessionState.read { request.getSession(false)?.getAttribute(it) }?.takeIf { it.stage == AdminSessionState.Stage.AUTHENTICATED }?.sessionGeneration

    private fun context(request: HttpServletRequest) = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45))

    private fun json(v: ApprovalView) = linkedMapOf(
        "approvalId" to v.approvalId, "kind" to v.kind, "targetActorId" to v.targetActorId, "targetSnapshot" to v.targetSnapshot, "grantedRole" to v.grantedRole,
        "causeApprovalId" to v.causeApprovalId, "requestedBy" to v.requestedBy, "requestedByDisplayName" to v.requestedByDisplayName,
        "requestedAt" to v.requestedAt.toString(), "expiresAt" to v.expiresAt.toString(), "status" to v.status, "decidedBy" to v.decidedBy,
        "decidedByDisplayName" to v.decidedByDisplayName, "decidedAt" to v.decidedAt?.toString(), "resultCode" to v.resultCode,
    )

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun status(status: HttpStatus, code: String, reason: String? = null): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(linkedMapOf("error" to code).also { m -> reason?.let { m["reason"] = it } })

    private companion object {
        /** 입력 형식 오류(Use Case가 TX 전에 판정 — S6 리뷰 m-2): 400. 업무 거절은 409. */
        const val INVALID_INPUT = "INVALID_INPUT"
    }

    class CreateRequest(val kind: String?, val targetActorId: String?, val grantedRole: String?, val causeApprovalId: String?)

    class TotpBody(val totpCode: String?) {
        override fun toString(): String = "TotpBody(***)"
    }
}

package com.paycore.admin.approval.adapter.web

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionState
import com.paycore.admin.approval.application.provided.AcquiringOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.RequestReacquisition
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.ApprovalView
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.JustificationResult
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestMerchantCodeChange
import com.paycore.admin.approval.application.provided.RequestMerchantContractApproval
import com.paycore.admin.approval.application.provided.RequestMerchantFirstMid
import com.paycore.admin.approval.application.provided.RequestMerchantCurrencyEnablement
import com.paycore.admin.approval.application.provided.RequestMerchantPaymentMethodActivation
import com.paycore.admin.approval.application.provided.RequestMerchantSiteReviewApproval
import com.paycore.admin.approval.application.provided.OperatorCancelJustificationQuery
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestOperatorCancel
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
class ApprovalController(
    private val approvals: ApprovalUseCase,
    private val owners: OwnerServiceApprovalUseCase,
    private val query: ApprovalQuery,
    private val justifications: OperatorCancelJustificationQuery,
    private val acquiring: AcquiringOpsApprovalUseCase,
    private val merchants: MerchantOpsApprovalUseCase,
) {

    @PostMapping
    fun request(@RequestBody body: CreateRequest, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = approvals.request(
            RequestApproval(authentication.name, generation, body.kind.orEmpty(), body.targetActorId.orEmpty(), body.grantedRole, body.causeApprovalId, context(request)),
        )
        return respond(outcome, request)
    }

    /** 운영자 보정 취소 요청(ADMIN-PAYMENT-OPS-001 §4.12 — OPERATOR). 금액은 JSON 정수(minor). 앞뒤 공백은 서버가 자르지 않고 거부한다(화면이 정리 — §4.18). */
    @PostMapping("/operator-correction-cancel")
    fun requestOperatorCancel(@RequestBody body: OperatorCancelBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = owners.requestOperatorCancel(
            RequestOperatorCancel(
                authentication.name, generation, body.paymentId.orEmpty(), body.amountMinor ?: 0, body.purposeKind.orEmpty(), body.purposeReference.orEmpty(),
                body.reason.orEmpty(), context(request),
            ),
        )
        return respond(outcome, request)
    }

    /**
     * 재매입 요청(ACQ-ADMIN-001 B1-A — OPERATOR, D-ACQA-002·003). 순번은 JSON 정수 배열(1..50, 오름차순). 앞뒤 공백은 서버가 자르지 않고 거부한다.
     * 응답: 201 approvalId / 400 INVALID_INPUT(field·reason)·ACQUISITION_FILE_NOT_FOUND / 403 / 409 TARGET_NOT_ELIGIBLE_ADVISORY·DUPLICATE_UNRESOLVED / 503 FINANCIAL_UNAVAILABLE.
     */
    @PostMapping("/acq-reacquisition")
    fun requestReacquisition(@RequestBody body: ReacquisitionBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = acquiring.requestReacquisition(
            RequestReacquisition(
                authentication.name, generation, body.fileId.orEmpty(), body.seqs.orEmpty(), body.reasonCode.orEmpty(), body.evidenceRef.orEmpty(), body.reason.orEmpty(),
                context(request),
            ),
        )
        return respond(outcome, request)
    }

    /**
     * 가맹점 계약 승인 요청(MERCHANT-OPS-002 B2-2 §9.5 — OPERATOR, D-MOPS-008 ① 근거 참조 필수). revisionNo는 JSON 정수. 앞뒤 공백은 서버가 자르지 않고 거부한다.
     * 응답: 201 approvalId / 400 INVALID_INPUT(field·reason) / 403 / 404 MERCHANT_NOT_FOUND / 409 TARGET_NOT_ELIGIBLE_ADVISORY·DUPLICATE_UNRESOLVED / 503 MASTER_UNAVAILABLE·AUDIT_UNAVAILABLE.
     */
    @PostMapping("/merchant-contract-approval")
    fun requestMerchantContractApproval(@RequestBody body: MerchantContractApprovalBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = merchants.requestContractApproval(
            RequestMerchantContractApproval(
                authentication.name, generation, body.merchantId.orEmpty(), body.contractId.orEmpty(), body.revisionNo ?: 0, body.evidenceRef.orEmpty(), context(request),
            ),
        )
        return respond(outcome, request)
    }

    /**
     * 첫 Default MID 발급 요청(MERCHANT-OPS-002 B2-3 §9.5 — OPERATOR, 근거 참조 필수). revisionNo는 JSON 정수. 응답은 계약 승인 요청과 같은 표.
     */
    @PostMapping("/merchant-first-mid")
    fun requestMerchantFirstMid(@RequestBody body: MerchantFirstMidBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = merchants.requestFirstMidIssuance(
            RequestMerchantFirstMid(
                authentication.name, generation, body.merchantId.orEmpty(), body.contractId.orEmpty(), body.agreementId.orEmpty(), body.revisionNo ?: 0,
                body.primarySiteId.orEmpty(), body.evidenceRef.orEmpty(), context(request),
            ),
        )
        return respond(outcome, request)
    }

    /** B3-1 결제수단 개통 승인 요청(MERCHANT-OPS-002 §9.16 — OPERATOR, 근거 참조 = 기관(카드사/VAN) 회신 참조 필수). 응답은 계약 승인 요청과 같은 표. */
    @PostMapping("/merchant-payment-method-activation")
    fun requestMerchantPaymentMethodActivation(
        @RequestBody body: MerchantActivationApprovalBody, authentication: Authentication, request: HttpServletRequest,
    ): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = merchants.requestPaymentMethodActivation(
            RequestMerchantPaymentMethodActivation(
                authentication.name, generation, body.merchantId.orEmpty(), body.merchantAccountId.orEmpty(), body.activationId.orEmpty(), body.evidenceRef.orEmpty(),
                context(request),
            ),
        )
        return respond(outcome, request)
    }

    /** B3-1 Site 심사 승인 요청(§9.16 — OPERATOR, 근거 참조 = PG 심사 근거 필수, 사유 `REVIEW_PASSED`). 거절은 1인 화면 API(`/api/merchant-registrations/site-review-rejections`). */
    @PostMapping("/merchant-site-review-approval")
    fun requestMerchantSiteReviewApproval(
        @RequestBody body: MerchantSiteReviewApprovalBody, authentication: Authentication, request: HttpServletRequest,
    ): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = merchants.requestSiteReviewApproval(
            RequestMerchantSiteReviewApproval(
                authentication.name, generation, body.merchantId.orEmpty(), body.merchantSiteId.orEmpty(), body.reasonCode.orEmpty(), body.evidenceRef.orEmpty(),
                context(request),
            ),
        )
        return respond(outcome, request)
    }

    /**
     * B3-2 통화 활성화 요청(MERCHANT-OPS-002 §9.16·§9.26 — OPERATOR, 근거 참조 = 기관 회신 참조 필수, D-MOPS-009 ②): `effectiveFromMode`(`AT_EXECUTION`·`DATE`) + `effectiveFromDate`
     * (DATE만, KST `YYYY-MM-DD` — 내일 이후) + `effectiveToDate`(선택). 응답은 계약 승인 요청과 같은 표.
     */
    @PostMapping("/merchant-currency-enablement")
    fun requestMerchantCurrencyEnablement(
        @RequestBody body: MerchantCurrencyEnablementBody, authentication: Authentication, request: HttpServletRequest,
    ): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = merchants.requestCurrencyEnablement(
            RequestMerchantCurrencyEnablement(
                authentication.name, generation, body.merchantId.orEmpty(), body.merchantAccountId.orEmpty(), body.currency.orEmpty(), body.effectiveFromMode.orEmpty(),
                body.effectiveFromDate, body.effectiveToDate, body.evidenceRef.orEmpty(), context(request),
            ),
        )
        return respond(outcome, request)
    }

    /**
     * MERCHANT-CODE-001 MC-1b 가맹점 코드 변경 요청(§5.5 — OPERATOR, 근거 참조 필수): 본문 `merchantId`, `newCode`, `reasonCode`, `evidenceRef` → 201 `{approvalId}`. 응답은 계약 승인 요청과 같은 표.
     */
    @PostMapping("/merchant-code-change")
    fun requestMerchantCodeChange(
        @RequestBody body: MerchantCodeChangeBody, authentication: Authentication, request: HttpServletRequest,
    ): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val outcome = merchants.requestMerchantCodeChange(
            RequestMerchantCodeChange(
                authentication.name, generation, body.merchantId.orEmpty(), body.newCode.orEmpty(), body.reasonCode.orEmpty(), body.evidenceRef.orEmpty(), context(request),
            ),
        )
        return respond(outcome, request)
    }

    /**
     * 승인(§4.10 M-6 0단계 분기): 승인 행 종류를 비잠금으로 읽어 소유 서비스면 2-TX 서비스로, ADMIN 소유면 기존 단일 TX로 보낸다. 분기는 비권위 —
     * 기존 경로는 잠근 행이 소유 서비스면 fail-closed(500 INTERNAL_INTEGRITY), 2-TX 경로는 ADMIN 소유 행을 거부한다.
     */
    @PostMapping("/{approvalId}/approve")
    fun approve(@PathVariable approvalId: String, @RequestBody body: TotpBody, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> {
        val generation = generation(request) ?: return status(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        val command = DecideApproval(approvalId, authentication.name, generation, body.totpCode.orEmpty(), context(request))
        return respond(if (query.isOwnerService(approvalId) == true) owners.approve(command) else approvals.approve(command), request)
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

    /**
     * 근거·사유 별도 권한 조회(D-OPS-010 — APPROVER 역할과 요청자 본인만, 서버가 계정 현재 행으로 판정): 200 문구 / 403 / 404 / 503 `AUDIT_UNAVAILABLE`(감사를 남기지 못하면
     * 보이지 않는다). 시도마다 감사 1행(문구 없음). 목록·상세(I-98)에는 계속 문구가 없다.
     * 재매입 승인(ACQ-ADMIN-001 D-ACQA-005)은 같은 판정·감사로 `{approvalId, evidenceRef}`만(사유 문구 없음 — I-4).
     */
    @GetMapping("/{approvalId}/justification")
    fun justification(@PathVariable approvalId: String, authentication: Authentication, request: HttpServletRequest): ResponseEntity<out Any> =
        when (val result = justifications.view(authentication.name, approvalId, context(request))) {
            is JustificationResult.Found -> ok(linkedMapOf("approvalId" to result.approvalId, "purposeReference" to result.purposeReference, "reason" to result.reason))
            is JustificationResult.EvidenceFound -> ok(linkedMapOf("approvalId" to result.approvalId, "evidenceRef" to result.evidenceRef))
            JustificationResult.Forbidden -> status(HttpStatus.FORBIDDEN, "FORBIDDEN")
            JustificationResult.NotFound -> status(HttpStatus.NOT_FOUND, "NOT_FOUND")
            JustificationResult.AuditUnavailable -> status(HttpStatus.SERVICE_UNAVAILABLE, "AUDIT_UNAVAILABLE")
            JustificationResult.RateLimited -> status(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        }

    private fun respond(outcome: ApprovalOutcome, request: HttpServletRequest): ResponseEntity<out Any> = when (outcome) {
        is ApprovalOutcome.Created -> ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(mapOf("approvalId" to outcome.approvalId))
        is ApprovalOutcome.Executed -> ok(linkedMapOf("approvalId" to outcome.approvalId, "status" to "EXECUTED").also { m -> outcome.enrollmentCode?.let { m["enrollmentCode"] = it } })
        is ApprovalOutcome.Closed -> ok(mapOf("status" to outcome.status))
        is ApprovalOutcome.Invalidated -> status(HttpStatus.CONFLICT, "INVALIDATED", outcome.code)
        is ApprovalOutcome.OwnerRejected -> status(HttpStatus.CONFLICT, "OWNER_REJECTED", outcome.code)
        is ApprovalOutcome.Rejected -> when {
            outcome.field != null && outcome.reason != null ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore())
                    .body(linkedMapOf("error" to outcome.code, "field" to outcome.field, "reason" to outcome.reason))
            outcome.code in BAD_REQUEST_CODES -> status(HttpStatus.BAD_REQUEST, outcome.code)
            outcome.code in NOT_FOUND_CODES -> status(HttpStatus.NOT_FOUND, outcome.code)
            else -> status(HttpStatus.CONFLICT, outcome.code)
        }
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
        is ApprovalOutcome.Unavailable -> status(HttpStatus.SERVICE_UNAVAILABLE, outcome.code)
        is ApprovalOutcome.Executing -> ok(mapOf("approvalId" to outcome.approvalId, "status" to outcome.status))
    }

    private fun generation(request: HttpServletRequest): Long? =
        AdminSessionState.read { request.getSession(false)?.getAttribute(it) }?.takeIf { it.stage == AdminSessionState.Stage.AUTHENTICATED }?.sessionGeneration

    private fun context(request: HttpServletRequest) = AdminRequestContext(UUID.randomUUID().toString(), request.remoteAddr?.take(45))

    private fun json(v: ApprovalView): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "approvalId" to v.approvalId, "kind" to v.kind, "targetKind" to v.targetKind, "targetActorId" to v.targetActorId, "targetRef" to v.targetRef, "targetSnapshot" to v.targetSnapshot, "grantedRole" to v.grantedRole,
        "causeApprovalId" to v.causeApprovalId, "requestedBy" to v.requestedBy, "requestedByDisplayName" to v.requestedByDisplayName,
        "requestedAt" to v.requestedAt.toString(), "expiresAt" to v.expiresAt.toString(), "status" to v.status, "decidedBy" to v.decidedBy,
        "decidedByDisplayName" to v.decidedByDisplayName, "decidedAt" to v.decidedAt?.toString(), "resultCode" to v.resultCode,
    ).also { m ->
        // S8 화면용 소유 서비스 상세(I-98 — 사유·근거 문구 없음). 고정 대상은 JSON 객체로 내보낸다.
        v.owner?.let { o ->
            m["owner"] = linkedMapOf(
                "amountMinor" to o.amountMinor, "currency" to o.currency, "purposeKind" to o.purposeKind, "pinnedTarget" to pinnedJson.readTree(o.pinnedTarget),
                "approvedAt" to o.approvedAt?.toString(), "executeBefore" to o.executeBefore?.toString(), "attemptCount" to o.attemptCount,
                "nextAttemptAt" to o.nextAttemptAt?.toString(), "lastResponseAt" to o.lastResponseAt?.toString(), "lastFailure" to o.lastFailure,
                "integrityFlag" to o.integrityFlag, "resendHaltedReason" to o.resendHaltedReason, "ownerResultCode" to o.ownerResultCode,
                "ownerReference" to o.ownerReference,
            )
        }
        v.merchant?.let { g ->
            m["merchant"] = linkedMapOf(
                // D-MOPS-008 ①: 근거 참조는 상세(4역할 공통)에 넣지 않는다 — APPROVER·요청자 본인만 `/justification` 별도 조회(감사). 고정 대상에 Revision 등록자가 있다.
                "commandKind" to g.commandKind, "merchantId" to g.merchantId, "pinnedTarget" to pinnedJson.readTree(g.pinnedTarget),
                "approvedAt" to g.approvedAt?.toString(), "executeBefore" to g.executeBefore?.toString(), "attemptCount" to g.attemptCount,
                "nextAttemptAt" to g.nextAttemptAt?.toString(), "lastResponseAt" to g.lastResponseAt?.toString(), "lastFailure" to g.lastFailure,
                "integrityFlag" to g.integrityFlag, "resendHaltedReason" to g.resendHaltedReason, "ownerResultCode" to g.ownerResultCode,
                "ownerReference" to g.ownerReference,
            )
        }
        v.acquiring?.let { a ->
            m["acquiring"] = linkedMapOf(
                // D-ACQA-005: 근거 참조는 상세(4역할 공통)에 넣지 않는다 — APPROVER·요청자 본인만 `/justification` 별도 조회(감사).
                "originalFileId" to a.originalFileId, "reasonCode" to a.reasonCode, "pinnedTarget" to pinnedJson.readTree(a.pinnedTarget),
                "approvedAt" to a.approvedAt?.toString(), "executeBefore" to a.executeBefore?.toString(), "attemptCount" to a.attemptCount,
                "nextAttemptAt" to a.nextAttemptAt?.toString(), "lastResponseAt" to a.lastResponseAt?.toString(), "lastFailure" to a.lastFailure,
                "integrityFlag" to a.integrityFlag, "resendHaltedReason" to a.resendHaltedReason, "ownerResultCode" to a.ownerResultCode,
                "ownerReference" to a.ownerReference,
            )
        }
    }

    private val pinnedJson = tools.jackson.databind.json.JsonMapper.builder().build()

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun status(status: HttpStatus, code: String, reason: String? = null): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(linkedMapOf("error" to code).also { m -> reason?.let { m["reason"] = it } })

    private companion object {
        /** 입력 형식 오류(Use Case가 TX 전에 판정 — S6 리뷰 m-2): 400. 업무 거절은 409. */
        const val INVALID_INPUT = "INVALID_INPUT"

        /** 400 code: 입력 형식, 종류 해석 단계의 실행 모델 거부(ADMIN-PAYMENT-OPS-001 §4.10 1행 — 소유 서비스 종류는 새 endpoint만), Payment 없음(§4.12 2). */
        val BAD_REQUEST_CODES = setOf(INVALID_INPUT, "WRONG_EXECUTION_MODEL", "PAYMENT_NOT_FOUND", "ACQUISITION_FILE_NOT_FOUND")

        /** 404 code: 가맹점 없음(MERCHANT-OPS-002 §9.5 — MASTER 상세 404). */
        val NOT_FOUND_CODES = setOf("MERCHANT_NOT_FOUND")
    }

    class CreateRequest(val kind: String?, val targetActorId: String?, val grantedRole: String?, val causeApprovalId: String?)

    class OperatorCancelBody(val paymentId: String?, val amountMinor: Long?, val purposeKind: String?, val purposeReference: String?, val reason: String?) {
        override fun toString(): String = "OperatorCancelBody(paymentId=$paymentId, amountMinor=$amountMinor)"
    }

    class ReacquisitionBody(val fileId: String?, val seqs: List<Int>?, val reasonCode: String?, val evidenceRef: String?, val reason: String?) {
        override fun toString(): String = "ReacquisitionBody(fileId=$fileId, seqs=${seqs?.size})"
    }

    class MerchantContractApprovalBody(val merchantId: String?, val contractId: String?, val revisionNo: Long?, val evidenceRef: String?) {
        override fun toString(): String = "MerchantContractApprovalBody(merchantId=$merchantId, contractId=$contractId, revisionNo=$revisionNo)"
    }

    class MerchantFirstMidBody(
        val merchantId: String?, val contractId: String?, val agreementId: String?, val revisionNo: Long?, val primarySiteId: String?, val evidenceRef: String?,
    ) {
        override fun toString(): String = "MerchantFirstMidBody(merchantId=$merchantId, contractId=$contractId, revisionNo=$revisionNo)"
    }

    class MerchantActivationApprovalBody(val merchantId: String?, val merchantAccountId: String?, val activationId: String?, val evidenceRef: String?) {
        override fun toString(): String = "MerchantActivationApprovalBody(merchantId=$merchantId, activationId=$activationId)"
    }

    class MerchantSiteReviewApprovalBody(val merchantId: String?, val merchantSiteId: String?, val reasonCode: String?, val evidenceRef: String?) {
        override fun toString(): String = "MerchantSiteReviewApprovalBody(merchantId=$merchantId, merchantSiteId=$merchantSiteId)"
    }

    class MerchantCurrencyEnablementBody(
        val merchantId: String?, val merchantAccountId: String?, val currency: String?, val effectiveFromMode: String?, val effectiveFromDate: String?,
        val effectiveToDate: String?, val evidenceRef: String?,
    ) {
        override fun toString(): String = "MerchantCurrencyEnablementBody(merchantId=$merchantId, merchantAccountId=$merchantAccountId, mode=$effectiveFromMode)"
    }

    class MerchantCodeChangeBody(val merchantId: String?, val newCode: String?, val reasonCode: String?, val evidenceRef: String?) {
        override fun toString(): String = "MerchantCodeChangeBody(merchantId=$merchantId, reasonCode=$reasonCode)"
    }

    class TotpBody(val totpCode: String?) {
        override fun toString(): String = "TotpBody(***)"
    }
}

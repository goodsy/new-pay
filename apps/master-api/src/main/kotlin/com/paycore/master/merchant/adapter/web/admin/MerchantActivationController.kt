package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ApprovedCommand
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * MERCHANT-OPS-002 B3-1 개통 명령(§9.14 — D-MOPS-007 ③, C-36·C-37·C-39·C-40·C-45). ADMIN 서명 채널(서명 v1·`ADMIN_SERVICE`·본문 16 KiB — D-MOPS-005) 뒤에서만 열리고 모두 POST·쓰기 permit,
 * ID는 서명 본문에 있다(C-2). 이 컨트롤러는 TX를 열지 않고 저장 Port를 직접 부르지 않는다(ArchUnit) — 1인 등록·1인 거절·근거 있는 2인 명령만 부른다(근거 없는 2인 메서드는 seed 전용).
 *
 * - 1인(B1 공통 필드 — `mo1.…`·`actor`): `payment-method-activations`(→ `act_…`), `site-review-rejections`(REJECTED 고정 — 결정값 필드 없음, → `site_…#n`).
 * - 2인(근거 공통 9 + `operation` + 명령 필드, 키 `mop1.<approvalId>`): `payment-method-activation-approvals`(→ `act_…`), `site-review-approvals`(APPROVED 고정, → `site_…#n`).
 *   기한 확인·결정 조회는 기존 [MerchantApprovalController] endpoint가 본문 `operation`으로 처리한다(C-39).
 *
 * 응답은 §3.3 그대로(거절에 `reference` 키 없음 — C-12). 로그·지표: 연산·결과 분류·상관 ID만(멱등 키 원문·근거 참조·행위자 없음). 지표 `command{operation,outcome}`(1인 거절 label은
 * `RejectSiteReview` — 2인 `DecideSiteReview`와 분리, nit-1), 2인은 `approval_executed{operation}`·`approval_rejected{operation,rejection}`(근거 거절 7종만 — B3-1F 등록자 거절 2종 포함).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/merchant-operations")
class MerchantActivationController(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val permits: AdminChannelPermits,
    private val settings: AdminApprovalSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(MerchantActivationController::class.java)

    /** 1인 수단 개통 등록(UNDER_REVIEW — 결제 허용 아님, D-MD-017). */
    @PostMapping("/payment-method-activations")
    fun registerPaymentMethodActivation(request: HttpServletRequest): ResponseEntity<ByteArray> = command(request, REGISTER) { body ->
        MerchantOperationsRequestParser.paymentMethodActivation(body)?.let { c -> c.request to { onboarding.registerPaymentMethodActivation(c) } }
    }

    /** 1인 Site 심사 거절(REJECTED 종결 — D-MS-005, 재신청은 새 Site). */
    @PostMapping("/site-review-rejections")
    fun rejectSiteReview(request: HttpServletRequest): ResponseEntity<ByteArray> = command(request, REJECT) { body ->
        MerchantOperationsRequestParser.siteReviewRejection(body)?.let { c -> c.request to { entitlement.rejectSiteReview(c) } }
    }

    /** 2인 수단 개통 승인(근거 경로 — PRIMARY Site 적용 확인 동반, C-45 PRIMARY 고정). */
    @PostMapping("/payment-method-activation-approvals")
    fun approvePaymentMethodActivation(request: HttpServletRequest): ResponseEntity<ByteArray> = approved(request, APPROVE_ACTIVATION) { body ->
        MerchantApprovalCommandParser.paymentMethodActivationApproval(body, settings.window)?.let { c -> c to { entitlement.approvePaymentMethodActivationWithEvidence(c) } }
    }

    /** 2인 Site 심사 승인(근거 경로 — APPROVED 고정). */
    @PostMapping("/site-review-approvals")
    fun approveSiteReview(request: HttpServletRequest): ResponseEntity<ByteArray> = approved(request, DECIDE_REVIEW) { body ->
        MerchantApprovalCommandParser.siteReviewApproval(body, settings.window)?.let { c -> c to { entitlement.approveSiteReviewWithEvidence(c) } }
    }

    private fun command(
        request: HttpServletRequest,
        operation: String,
        parse: (ByteArray) -> Pair<AdminRequest, () -> AdminCommandResult>?,
    ): ResponseEntity<ByteArray> = permits.write(operation, { busy(request) }) {
        val (adminRequest, execute) = parse(request.inputStream.readAllBytes()) ?: return@write malformed(request, operation)
        guarded(request, operation, adminRequest.correlationId) { respond(request, operation, adminRequest.correlationId, execute()) }
    }

    private fun <C : ApprovedCommand> approved(
        request: HttpServletRequest,
        operation: String,
        parse: (ByteArray) -> Pair<C, () -> AdminCommandResult>?,
    ): ResponseEntity<ByteArray> = permits.write(operation, { busy(request) }) {
        val (command, execute) = parse(request.inputStream.readAllBytes()) ?: return@write malformed(request, operation)
        guarded(request, operation, command.request.correlationId) {
            val result = execute()
            observe(command, result)
            respond(request, operation, command.request.correlationId, result)
        }
    }

    /** Use Case 실패 분류(다른 관리 채널 컨트롤러와 같은 매핑 — 예외 메시지·원인은 남기지 않는다). */
    private fun guarded(request: HttpServletRequest, operation: String, correlationId: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        try {
            block()
        } catch (e: AdminCommandFailure) {
            val code = when (e) {
                is AdminCommandFailure.RequestInProgress -> AdminChannelResponses.REQUEST_IN_PROGRESS
                is AdminCommandFailure.OutcomeUnknown -> AdminChannelResponses.OUTCOME_UNKNOWN
                is AdminCommandFailure.IdentifierCollision, is AdminCommandFailure.TemporarilyUnavailable -> AdminChannelResponses.TEMPORARILY_UNAVAILABLE
            }
            count(operation, FAILED)
            log.warn("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, code)
            AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, code)
        } catch (e: RuntimeException) {
            count(operation, FAILED)
            log.warn(
                "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                operation, AdminChannelResponses.requestId(request), correlationId, e.javaClass.simpleName,
            )
            AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
        }

    private fun respond(request: HttpServletRequest, operation: String, correlationId: String, result: AdminCommandResult): ResponseEntity<ByteArray> {
        val (outcome, response) = when (result) {
            is AdminCommandResult.Succeeded -> SUCCEEDED to AdminChannelResponses.json(
                request, OK, linkedMapOf("outcome" to SUCCEEDED, "reference" to result.reference, "replayed" to result.replayed),
            )
            // C-12: 거절 reference는 여기서 버린다(키 자체를 만들지 않는다).
            is AdminCommandResult.Rejected -> REJECTED to AdminChannelResponses.json(
                request, OK, linkedMapOf("outcome" to REJECTED, "rejection" to result.rejection.name, "replayed" to result.replayed),
            )
            AdminCommandResult.IdempotencyKeyReused -> KEY_REUSED to AdminChannelResponses.error(request, CONFLICT, AdminChannelResponses.IDEMPOTENCY_KEY_REUSED)
        }
        count(operation, outcome)
        log.info("operation={} requestId={} correlationId={} result={}", operation, AdminChannelResponses.requestId(request), correlationId, outcome)
        return response
    }

    /** 근거 지표(첫 판정만 — 재생 제외). label은 operation code와 근거 거절 7종만([MerchantApprovalController.APPROVAL_REJECTIONS]). */
    private fun observe(command: ApprovedCommand, result: AdminCommandResult) {
        when (result) {
            is AdminCommandResult.Succeeded -> if (!result.replayed) {
                registry.counter(MerchantApprovalController.APPROVAL_EXECUTED_METRIC, "operation", command.operation.code).increment()
            }
            is AdminCommandResult.Rejected -> if (!result.replayed && result.rejection in MerchantApprovalController.APPROVAL_REJECTIONS) {
                registry.counter(MerchantApprovalController.APPROVAL_REJECTED_METRIC, "operation", command.operation.code, "rejection", result.rejection.name).increment()
            }
            AdminCommandResult.IdempotencyKeyReused -> Unit
        }
    }

    private fun malformed(request: HttpServletRequest, operation: String): ResponseEntity<ByteArray> {
        log.info("operation={} requestId={} result=REQUEST_MALFORMED", operation, AdminChannelResponses.requestId(request))
        return AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
    }

    private fun busy(request: HttpServletRequest): ResponseEntity<ByteArray> =
        AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_WRITE_BUSY)

    private fun count(operation: String, outcome: String) {
        registry.counter(MerchantOperationsController.COMMAND_METRIC, "operation", operation, "outcome", outcome).increment()
    }

    private companion object {
        const val REGISTER = "RegisterPaymentMethodActivation"
        const val REJECT = "RejectSiteReview"
        const val APPROVE_ACTIVATION = "ApprovePaymentMethodActivation"
        const val DECIDE_REVIEW = "DecideSiteReview"
        const val SUCCEEDED = "SUCCEEDED"
        const val REJECTED = "REJECTED"
        const val KEY_REUSED = "KEY_REUSED"
        const val FAILED = "FAILED"
        const val OK = 200
        const val BAD_REQUEST = 400
        const val CONFLICT = 409
        const val SERVICE_UNAVAILABLE = 503
    }
}

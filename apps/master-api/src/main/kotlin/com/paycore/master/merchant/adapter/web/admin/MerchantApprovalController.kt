package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApprovalDecision
import com.paycore.master.merchant.application.provided.ApprovalDecisionUseCase
import com.paycore.master.merchant.application.provided.ApprovalOutcome
import com.paycore.master.merchant.application.provided.ApprovedCommand
import com.paycore.master.merchant.application.provided.DeadlineConfirmationResult
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.MasterRejection
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 2인 승인 명령(MERCHANT-OPS-002 §9.4 B2-2 — D-MOPS-007 ①·D-MOPS-008, C-17·C-18·C-23). ADMIN 서명 채널(서명 v1·`ADMIN_SERVICE`·본문 16 KiB·query 금지 — 새 키·권한 없음, D-MOPS-005) 뒤에서만
 * 열리고 모두 POST, ID는 서명 본문에 있다(C-2). 이 컨트롤러는 TX를 열지 않고 저장 Port를 직접 부르지 않는다(ArchUnit) — 근거 있는 명령과 결정 조회 Use Case만 부른다.
 *
 * - `contract-revision-approvals`(실행, 쓰기 permit): [MerchantApprovalCommandParser] → [MerchantOnboardingUseCase.approveContractRevisionWithEvidence]. 응답 §3.3 그대로 —
 *   `{"outcome":"SUCCEEDED","reference":"ctr_…#n","replayed":…}` / `{"outcome":"REJECTED","rejection":…,"replayed":…}`(**거절에 `reference` 키 없음**, C-12) / 409 `IDEMPOTENCY_KEY_REUSED` /
 *   503(`REQUEST_IN_PROGRESS`·`OUTCOME_UNKNOWN`·`TEMPORARILY_UNAVAILABLE`·`ADMIN_WRITE_BUSY`) / 400 `REQUEST_MALFORMED`(저장 없음).
 * - `approval-deadline-confirmations`(기한 확인, **쓰기 permit 공유** — 실행 경로 없음): 실행과 같은 저장 바이트. 기한 전 200 `{"decision":"NOT_YET_DUE"}`(아무것도 저장 안 함),
 *   그 밖 결정은 실행과 같은 §3.3 모양(재생 포함). 진행 중 503 `REQUEST_IN_PROGRESS`.
 * - `approval-decisions`(결정 조회, **조회 permit** — 쓰기 효과 없음): 본문 `approvalId`·`correlationId`만. `{"decision":"NONE"}` 또는
 *   `{"decision":"DECIDED","operation","outcome":"SUCCEEDED|REJECTED","reference"(성공만),"rejection"(거절만),"approvalFingerprint","decidedVia"}`. NONE은 효과 없음의 근거가 아니다.
 *
 * 승인 창([AdminApprovalSettings], 기본값 없음)은 이 adapter가 명령에 넣는다. 로그·지표: 연산·결과 분류·상관 ID(`mop.<approvalId>`)만 — 멱등 키 원문·서명·근거 참조 값·행위자 없음.
 * 지표 `paycore.master.admin_channel.command{operation,outcome}`(B1과 같은 이름), `approval_rejected{operation,rejection}`(근거 거절 7종만 label — 첫 판정만, 재생 제외),
 * `approval_executed{operation}`(첫 실행 성공만 — ADMIN `owner_executed{kind}`와 대조할 수 있게, m-3).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin/merchant-operations")
class MerchantApprovalController(
    private val onboarding: MerchantOnboardingUseCase,
    private val decisions: ApprovalDecisionUseCase,
    private val permits: AdminChannelPermits,
    private val settings: AdminApprovalSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(MerchantApprovalController::class.java)

    /** B2-2 계약 Revision 승인(2인 — 근거 있는 경로만, C-26). 성공 reference `ctr_…#<n>`. */
    @PostMapping("/contract-revision-approvals")
    fun approveContractRevision(request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.write(EXECUTE, { busy(request, AdminChannelResponses.ADMIN_WRITE_BUSY) }) {
            val command = MerchantApprovalCommandParser.contractRevisionApproval(request.inputStream.readAllBytes(), settings.window)
                ?: return@write malformed(request, EXECUTE)
            guarded(request, EXECUTE, command.request.correlationId) {
                val result = onboarding.approveContractRevisionWithEvidence(command)
                observe(command, result, executed = true)
                commandResponse(request, EXECUTE, command.request.correlationId, result)
            }
        }

    /** B2-3 첫 Default MID 발급(2인 — 근거 있는 경로만, C-26). 성공 reference `mid_…`. Revision 등록자 규칙은 적용하지 않는다(§9.3 판정 5는 계약 승인만). */
    @PostMapping("/first-default-merchant-accounts")
    fun issueFirstDefaultMerchantAccount(request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.write(ISSUE, { busy(request, AdminChannelResponses.ADMIN_WRITE_BUSY) }) {
            val command = MerchantApprovalCommandParser.firstDefaultIssuance(request.inputStream.readAllBytes(), settings.window)
                ?: return@write malformed(request, ISSUE)
            guarded(request, ISSUE, command.request.correlationId) {
                val result = onboarding.issueFirstDefaultMerchantAccountWithEvidence(command)
                observe(command, result, executed = true)
                commandResponse(request, ISSUE, command.request.correlationId, result)
            }
        }

    /** 기한 확인 전용(§9.4 — 실행 본문 없음). */
    @PostMapping("/approval-deadline-confirmations")
    fun confirmDeadline(request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.write(DEADLINE, { busy(request, AdminChannelResponses.ADMIN_WRITE_BUSY) }) {
            val command = MerchantApprovalCommandParser.deadlineConfirmation(request.inputStream.readAllBytes(), settings.window)
                ?: return@write malformed(request, DEADLINE)
            guarded(request, DEADLINE, command.request.correlationId) {
                when (val outcome = decisions.confirmDeadline(command)) {
                    DeadlineConfirmationResult.NotYetDue -> {
                        count(DEADLINE, NOT_YET_DUE)
                        log.info("operation={} requestId={} correlationId={} result={}", DEADLINE, AdminChannelResponses.requestId(request), command.request.correlationId, NOT_YET_DUE)
                        AdminChannelResponses.json(request, OK, linkedMapOf("decision" to NOT_YET_DUE))
                    }
                    is DeadlineConfirmationResult.Decided -> {
                        observe(command, outcome.result, executed = false)
                        commandResponse(request, DEADLINE, command.request.correlationId, outcome.result)
                    }
                }
            }
        }

    /** 결정 조회(§9.4 — 근거 행 한 행 읽기, 쓰기 0). */
    @PostMapping("/approval-decisions")
    fun decision(request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.read(DECISION_ENDPOINT, { busy(request, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            val approvalId = MerchantApprovalCommandParser.decisionApprovalId(request.inputStream.readAllBytes())
                ?: return@read malformed(request, DECISION)
            val correlationId = "$CORRELATION_PREFIX$approvalId"
            try {
                val body = when (val decision = decisions.decision(approvalId)) {
                    ApprovalDecision.None -> linkedMapOf<String, Any?>("decision" to "NONE")
                    is ApprovalDecision.Decided -> decided(decision)
                }
                log.info("operation={} requestId={} correlationId={} result={}", DECISION, AdminChannelResponses.requestId(request), correlationId, body["decision"])
                AdminChannelResponses.json(request, OK, body)
            } catch (e: RuntimeException) {
                log.warn(
                    "operation={} requestId={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}",
                    DECISION, AdminChannelResponses.requestId(request), correlationId, e.javaClass.simpleName,
                )
                AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
            }
        }

    private fun decided(d: ApprovalDecision.Decided): Map<String, Any?> = linkedMapOf<String, Any?>(
        "decision" to "DECIDED",
        "operation" to d.operation.code,
        "outcome" to if (d.outcome == ApprovalOutcome.EXECUTED) SUCCEEDED else REJECTED,
    ).apply {
        if (d.outcome == ApprovalOutcome.EXECUTED) put("reference", d.reference) else put("rejection", d.rejection?.name)
        put("approvalFingerprint", d.approvalFingerprint)
        put("decidedVia", d.decidedVia.name)
    }

    /** Use Case 실패 분류(B1 명령 컨트롤러와 같은 매핑 — 예외 메시지·원인은 남기지 않는다: 드라이버 원문에 바인드 값이 섞일 수 있다). */
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

    private fun commandResponse(request: HttpServletRequest, operation: String, correlationId: String, result: AdminCommandResult): ResponseEntity<ByteArray> {
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

    /** 근거 지표(첫 판정만 — 재생은 새 결정이 아니다). label은 operation code와 근거 거절 5종만. */
    private fun observe(command: ApprovedCommand, result: AdminCommandResult, executed: Boolean) {
        when (result) {
            is AdminCommandResult.Succeeded -> if (executed && !result.replayed) {
                registry.counter(APPROVAL_EXECUTED_METRIC, "operation", command.operation.code).increment()
            }
            is AdminCommandResult.Rejected -> if (!result.replayed && result.rejection in APPROVAL_REJECTIONS) {
                registry.counter(APPROVAL_REJECTED_METRIC, "operation", command.operation.code, "rejection", result.rejection.name).increment()
            }
            AdminCommandResult.IdempotencyKeyReused -> Unit
        }
    }

    private fun malformed(request: HttpServletRequest, operation: String): ResponseEntity<ByteArray> {
        log.info("operation={} requestId={} result=REQUEST_MALFORMED", operation, AdminChannelResponses.requestId(request))
        return AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
    }

    private fun busy(request: HttpServletRequest, code: String): ResponseEntity<ByteArray> = AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, code)

    private fun count(operation: String, outcome: String) {
        registry.counter(MerchantOperationsController.COMMAND_METRIC, "operation", operation, "outcome", outcome).increment()
    }

    companion object {
        const val APPROVAL_REJECTED_METRIC = "paycore.master.admin_channel.approval_rejected"
        const val APPROVAL_EXECUTED_METRIC = "paycore.master.admin_channel.approval_executed"

        /** 지표 label로 쓰는 근거 거절(§9.3 판정 1~5 + §9.27 B3-1F 등록자 거절 2종 = 7종, B3-3에서 Route 등록자 1종을 더해 8종). */
        val APPROVAL_REJECTIONS: Set<MasterRejection> = setOf(
            MasterRejection.APPROVAL_SAME_ACTOR, MasterRejection.APPROVAL_WINDOW_INVALID, MasterRejection.APPROVAL_ID_ALREADY_USED,
            MasterRejection.APPROVAL_EXPIRED, MasterRejection.APPROVER_IS_REVISION_REGISTRANT,
            MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT, MasterRejection.APPROVER_IS_SITE_REGISTRANT,
        )

        private const val EXECUTE = "ApproveContractRevision"
        private const val ISSUE = "IssueFirstDefaultMerchantAccount"
        private const val DEADLINE = "ConfirmApprovalDeadline"
        private const val DECISION = "ApprovalDecision"
        private const val DECISION_ENDPOINT = "approval-decision"
        private const val CORRELATION_PREFIX = "mop."
        private const val SUCCEEDED = "SUCCEEDED"
        private const val REJECTED = "REJECTED"
        private const val KEY_REUSED = "KEY_REUSED"
        private const val FAILED = "FAILED"
        private const val NOT_YET_DUE = "NOT_YET_DUE"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val CONFLICT = 409
        private const val SERVICE_UNAVAILABLE = 503
    }
}

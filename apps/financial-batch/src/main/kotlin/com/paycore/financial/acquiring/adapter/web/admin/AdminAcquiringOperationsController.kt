package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.acquiring.application.provided.ExecuteReacquisitionUseCase
import com.paycore.financial.acquiring.application.provided.ReacquisitionExecution
import com.paycore.financial.acquiring.application.provided.ReacquisitionTargetQuery
import com.paycore.financial.acquiring.application.provided.ReacquisitionTargets
import com.paycore.financial.acquiring.domain.AcquisitionOpsDecision
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 매입 조작(ACQ-ADMIN-001 B1 — 재매입) ADMIN 채널 endpoint. 조작 경로 `/internal/v1/admin/acquiring-operations/`는 **조작 전용 키 서명만**(D-ACQA-001 — 보안 체인이 권한
 * `ADMIN_OPERATION`을 요구), 대상 미리 보기는 조회 경로(조회 키 — 비권위, 잠금 없음).
 *
 * 응답 불변식(ADMIN-PAYMENT-OPS-001 §4.4.1 선례): 결정 응답(200)은 **커밋된 결정 행**이 뒷받침한다. 409 `APPROVAL_CONFLICT` = 같은 승인 ID에 다른 내용의 결정이 있다(파생 —
 * 결정 아님). 400 `REQUEST_MALFORMED` = 같은 바이트면 언제나 같은 판정. 그 밖(저장소 실패·commit 결과 모름·busy)은 503 — 다시 보내면 그때 판정한다(멱등: 결정 기록).
 * 로그는 연산·결과 분류만(승인 ID는 내부 ID — 파일 ID·거래 ID·금액 없음).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/internal/v1/admin")
class AdminAcquiringOperationsController(
    private val execution: ExecuteReacquisitionUseCase,
    private val targets: ReacquisitionTargetQuery,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(AdminAcquiringOperationsController::class.java)

    @PostMapping("/acquiring-operations/reacquisitions")
    fun execute(request: HttpServletRequest): ResponseEntity<ByteArray> = operation(request, EXECUTE) {
        val command = ReacquisitionCommandParser.command(request.inputStream.readAllBytes())
            ?: return@operation AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
        respond(request, EXECUTE, execution.execute(command))
    }

    @GetMapping("/acquiring-operations/decisions/{approvalId}")
    fun decision(@PathVariable approvalId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = operation(request, DECISION) {
        if (!ReacquisitionCommandParser.APPROVAL_ID.matches(approvalId)) {
            return@operation AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
        }
        val found = execution.decision(approvalId)
        AdminChannelResponses.json(request, OK, if (found == null) none(approvalId) else body(found, replayed = true, confirmed = false))
    }

    @PostMapping("/acquiring-operations/decisions/{approvalId}/deadline-confirmation")
    fun confirmDeadline(@PathVariable approvalId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = operation(request, DEADLINE) {
        val command = ReacquisitionCommandParser.command(request.inputStream.readAllBytes())
        if (command == null || command.approvalId != approvalId) {
            return@operation AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
        }
        respond(request, DEADLINE, execution.confirmDeadline(command))
    }

    @PostMapping("/acquisition-files/{fileId}/reacquisition-targets")
    fun preview(@PathVariable fileId: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.read(PREVIEW, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            guarded(request, PREVIEW) {
                if (!AcquisitionSearchRequestParser.FILE_ID.matches(fileId)) {
                    return@guarded AdminChannelResponses.error(request, NOT_FOUND, AdminChannelResponses.ACQUISITION_FILE_NOT_FOUND)
                }
                val seqs = ReacquisitionCommandParser.seqs(request.inputStream.readAllBytes())
                    ?: return@guarded AdminChannelResponses.error(request, BAD_REQUEST, AdminChannelResponses.REQUEST_MALFORMED)
                val found = targets.targets(fileId, seqs)
                    ?: return@guarded AdminChannelResponses.error(request, NOT_FOUND, AdminChannelResponses.ACQUISITION_FILE_NOT_FOUND)
                AdminChannelResponses.json(request, OK, targetsBody(found))
            }
        }

    private fun respond(request: HttpServletRequest, endpoint: String, result: ReacquisitionExecution): ResponseEntity<ByteArray> {
        registry.counter(RESULT_METRIC, "endpoint", endpoint, "result", resultLabel(result)).increment()
        log.info("operation=AcquiringOperation endpoint={} correlationId={} result={}", endpoint, AdminChannelResponses.requestId(request), resultLabel(result))
        return when (result) {
            is ReacquisitionExecution.Decided -> AdminChannelResponses.json(request, OK, body(result.decision, result.replayed, result.confirmed))
            ReacquisitionExecution.Conflict -> AdminChannelResponses.error(request, CONFLICT, APPROVAL_CONFLICT)
            ReacquisitionExecution.NotDecided -> AdminChannelResponses.json(request, OK, mapOf("decision" to NONE, "confirmed" to false))
        }
    }

    private fun resultLabel(result: ReacquisitionExecution): String = when (result) {
        is ReacquisitionExecution.Decided -> result.decision.outcome.name
        ReacquisitionExecution.Conflict -> APPROVAL_CONFLICT
        ReacquisitionExecution.NotDecided -> NONE
    }

    private fun operation(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        permits.operation(endpoint, { AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, ADMIN_OPERATION_BUSY) }) { guarded(request, endpoint, block) }

    private fun guarded(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> = try {
        block()
    } catch (e: RuntimeException) {
        // 저장소 실패·commit 결과 모름·계약 위반 저장 행: 결정이 없거나 모른다 — 503(재전송하면 결정 기록으로 수렴). 메시지·원인은 남기지 않는다(분류만).
        log.warn("operation=AcquiringOperation endpoint={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", endpoint, AdminChannelResponses.requestId(request), e.javaClass.simpleName)
        AdminChannelResponses.error(request, SERVICE_UNAVAILABLE, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
    }

    private fun none(approvalId: String) = mapOf("decision" to NONE, "approvalId" to approvalId)

    private fun body(d: AcquisitionOpsDecision, replayed: Boolean, confirmed: Boolean): Map<String, Any?> = linkedMapOf(
        "decision" to d.outcome.name,
        "approvalId" to d.approvalId,
        "kind" to d.kind.name,
        "code" to d.rejectionCode,
        "targetRef" to d.targetRef,
        "resultFileId" to d.resultFileId?.value,
        "contentHash" to d.contentHash,
        "requestedBy" to d.requestedBy,
        "approvedBy" to d.approvedBy,
        "approvedAt" to AdminChannelResponses.time(d.approvedAt),
        "executeBefore" to AdminChannelResponses.time(d.executeBefore),
        "decidedAt" to AdminChannelResponses.time(d.decidedAt),
        "origin" to d.origin.name,
        "replayed" to replayed,
        "confirmed" to confirmed,
    )

    private fun targetsBody(t: ReacquisitionTargets): Map<String, Any?> = linkedMapOf(
        "fileId" to t.fileId,
        "readAt" to AdminChannelResponses.time(t.readAt),
        "items" to t.items.map {
            linkedMapOf(
                "seq" to it.seq, "transactionId" to it.transactionId, "recordType" to it.recordType, "generation" to it.generation, "status" to it.status,
                "eligible" to it.eligible, "refusal" to it.refusal, "approvedAmountMinor" to it.approvedAmountMinor, "cancelledAmountMinor" to it.cancelledAmountMinor,
                "expectedNetMinor" to it.expectedNetMinor,
            )
        },
    )

    companion object {
        const val EXECUTE = "reacquisition_execute"
        const val DECISION = "ops_decision"
        const val DEADLINE = "ops_deadline_confirmation"
        const val PREVIEW = "reacquisition_targets"
        const val RESULT_METRIC = "paycore.financial.admin_channel.operation"
        const val APPROVAL_CONFLICT = "APPROVAL_CONFLICT"
        const val ADMIN_OPERATION_BUSY = "ADMIN_OPERATION_BUSY"
        const val NONE = "NONE"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val SERVICE_UNAVAILABLE = 503
    }
}

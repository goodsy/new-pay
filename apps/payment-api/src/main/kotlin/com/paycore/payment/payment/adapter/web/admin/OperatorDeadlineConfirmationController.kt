package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.ApiResponses
import com.paycore.payment.payment.adapter.web.SafeThrowable
import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.ConfirmOperatorApprovalDeadlineUseCase
import com.paycore.payment.payment.application.provided.OperatorCancelMalformedRecorder
import com.paycore.payment.payment.application.provided.OperatorDecisionState
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 기한 확인 전용 진입점(ADMIN-PAYMENT-OPS-001 §4.4.5, D-OPS-009 — ADMIN 채널 서명 인증 뒤, ArchUnit 정확 FQN). 본문은 실행 명령과 같은 엄격 형식이고 경로 approvalId = 본문 approvalId.
 * 이 endpoint는 409·404를 쓰지 않는다: 기록 결정·NONE은 200(결정 조회 모양 + `confirmed`), 형식 오류 400 + 감사, 그 밖 503. 명령 permit을 실행과 공유한다(rev 0.4 L-3).
 */
@RestController
@RequestMapping("/internal/v1/admin/operator-cancellations")
class OperatorDeadlineConfirmationController(
    private val useCase: ConfirmOperatorApprovalDeadlineUseCase,
    private val recorder: OperatorCancelMalformedRecorder,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(OperatorDeadlineConfirmationController::class.java)

    @PostMapping("/{approvalId}/deadline-confirmation")
    fun confirm(@PathVariable approvalId: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.command(ENDPOINT, { respond(request, 503, AdminChannelResponses.ADMIN_COMMAND_BUSY) }) { handle(approvalId, request) }

    private fun handle(approvalId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = try {
        when (val parsed = AdminOperatorCancellationRequestParser.parse(request.inputStream.readAllBytes(), ApiResponses.requestId(request), pathApprovalId = approvalId)) {
            is AdminOperatorCancellationRequestParser.Parsed.Malformed -> {
                recorder.record(parsed.audit)
                respond(request, 400, parsed.audit.code.name)
            }
            is AdminOperatorCancellationRequestParser.Parsed.Ok -> {
                val r = useCase.confirm(parsed.command)
                registry.counter(REQUEST_METRIC, "endpoint", ENDPOINT, "result", "200").increment()
                // I-59: 이번에 기록한 기한 거절은 재생 아님, 기존 결정을 돌려준 것은 재생, NONE은 결정이 아니다.
                if (r.decision.state != OperatorDecisionState.NONE) AdminChannelDecisionMetric.record(registry, r.decision.state.name, (!r.confirmed).toString())
                AdminChannelResponses.json(request, 200, AdminChannelResponses.decision(r.decision).also { it["confirmed"] = r.confirmed })
            }
        }
    } catch (e: CancelPaymentFailure.OutcomeUnknown) {
        unavailable(request, AdminChannelResponses.OUTCOME_UNKNOWN, e)
    } catch (e: RuntimeException) {
        unavailable(request, AdminChannelResponses.TEMPORARILY_UNAVAILABLE, e)
    }

    private fun unavailable(request: HttpServletRequest, code: String, e: RuntimeException): ResponseEntity<ByteArray> {
        log.warn("operation=ConfirmOperatorApprovalDeadline correlationId={} result={} failure={}", ApiResponses.requestId(request), code, SafeThrowable.summary(e))
        return respond(request, 503, code)
    }

    private fun respond(request: HttpServletRequest, status: Int, code: String): ResponseEntity<ByteArray> {
        registry.counter(REQUEST_METRIC, "endpoint", ENDPOINT, "result", status.toString()).increment()
        return AdminChannelResponses.error(request, status, code)
    }

    private companion object {
        const val ENDPOINT = "deadline_confirmation"
        const val REQUEST_METRIC = "paycore.payment.admin_channel.request"
    }
}

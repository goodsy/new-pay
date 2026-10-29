package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.ApiResponses
import com.paycore.payment.payment.adapter.web.SafeThrowable
import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.OperatorCancelMalformedRecorder
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자 보정 취소 실행 진입점(ADMIN-PAYMENT-OPS-001 §4.3·§4.5 — ADMIN 채널 서명 인증 뒤에만 닿는다, ArchUnit 정확 FQN). HTTP 매핑 불변식: 409·404는 커밋된 결정 행이 뒷받침할 때만(Use Case
 * Rejected·NotFound), 400은 같은 바이트면 항상 같은 형식 판정(+ 감사 1행), 그 밖(일시 실패·commit 불명확·busy·감사 저장 실패·예상 못한 예외)은 503 — 500으로 내보내지 않는다.
 */
@RestController
@RequestMapping("/internal/v1/admin/operator-cancellations")
class AdminOperatorCancellationController(
    private val useCase: OperatorCorrectionCancelUseCase,
    private val recorder: OperatorCancelMalformedRecorder,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(AdminOperatorCancellationController::class.java)

    @PostMapping
    fun execute(request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.command(ENDPOINT, { respond(request, 503, AdminChannelResponses.ADMIN_COMMAND_BUSY) }) { handle(request) }

    private fun handle(request: HttpServletRequest): ResponseEntity<ByteArray> = try {
        when (val parsed = AdminOperatorCancellationRequestParser.parse(request.inputStream.readAllBytes(), ApiResponses.requestId(request))) {
            is AdminOperatorCancellationRequestParser.Parsed.Malformed -> {
                recorder.record(parsed.audit)
                respond(request, 400, parsed.audit.code.name)
            }
            is AdminOperatorCancellationRequestParser.Parsed.Ok -> when (val r = useCase.cancel(parsed.command)) {
                is OperatorCorrectionCancelResult.Accepted -> {
                    registry.counter(REQUEST_METRIC, "endpoint", ENDPOINT, "result", "200").increment()
                    AdminChannelDecisionMetric.record(registry, "STARTED", r.existing.toString())
                    AdminChannelResponses.json(
                        request, 200, linkedMapOf("cancellationId" to r.view.cancellationId.value, "status" to r.view.status.name, "existing" to r.existing),
                    )
                }
                is OperatorCorrectionCancelResult.Rejected -> {
                    if (!r.code.derived) AdminChannelDecisionMetric.record(registry, "REJECTED", r.replayed.toString())
                    respond(request, 409, r.code.name)
                }
                OperatorCorrectionCancelResult.NotFound -> {
                    AdminChannelDecisionMetric.record(registry, "REJECTED", "unknown")
                    respond(request, 404, AdminChannelResponses.PAYMENT_NOT_FOUND)
                }
            }
        }
    } catch (e: CancelPaymentFailure.OutcomeUnknown) {
        unavailable(request, AdminChannelResponses.OUTCOME_UNKNOWN, e)
    } catch (e: RuntimeException) {
        unavailable(request, AdminChannelResponses.TEMPORARILY_UNAVAILABLE, e)
    }

    private fun unavailable(request: HttpServletRequest, code: String, e: RuntimeException): ResponseEntity<ByteArray> {
        log.warn("operation=AdminOperatorCancellation correlationId={} result={} failure={}", ApiResponses.requestId(request), code, SafeThrowable.summary(e))
        return respond(request, 503, code)
    }

    private fun respond(request: HttpServletRequest, status: Int, code: String): ResponseEntity<ByteArray> {
        registry.counter(REQUEST_METRIC, "endpoint", ENDPOINT, "result", status.toString()).increment()
        return AdminChannelResponses.error(request, status, code)
    }

    private companion object {
        const val ENDPOINT = "execute"
        const val REQUEST_METRIC = "paycore.payment.admin_channel.request"
    }
}

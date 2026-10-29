package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.ApiResponses
import com.paycore.payment.payment.adapter.web.SafeThrowable
import com.paycore.payment.payment.application.provided.OperatorDecisionQuery
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import com.paycore.payment.payment.domain.OperatorApprovalId
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 기록 결정 조회·대사용 결정 목록(ADMIN-PAYMENT-OPS-001 §4.3 L-5·§4.17, D-OPS-008 — 비권위 jOOQ, ADMIN 채널 서명 인증 뒤, ArchUnit 정확 FQN). 조회 permit 공용. 형식이 아닌 approvalId·
 * watermark는 400 `REQUEST_MALFORMED`(감사 없음 — 조회, I-54), 질의 시간 초과·저장소 실패 503.
 */
@RestController
@RequestMapping("/internal/v1/admin/")
class OperatorDecisionController(
    private val query: OperatorDecisionQuery,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(OperatorDecisionController::class.java)

    @GetMapping("operator-cancellations/{approvalId}")
    fun decision(@PathVariable approvalId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = read(request, "decision") {
        if (!AdminOperatorCancellationRequestParser.APPROVAL_ID.matches(approvalId)) return@read AdminChannelResponses.error(request, 400, AdminChannelResponses.REQUEST_MALFORMED)
        AdminChannelResponses.json(request, 200, AdminChannelResponses.decision(query.decision(OperatorApprovalId(approvalId))))
    }

    @GetMapping("operator-decisions/after/{watermark}")
    fun after(@PathVariable watermark: String, request: HttpServletRequest): ResponseEntity<ByteArray> = read(request, "decision_list") {
        val from = watermark.takeIf { WATERMARK.matches(it) }?.toLongOrNull()
            ?: return@read AdminChannelResponses.error(request, 400, AdminChannelResponses.REQUEST_MALFORMED)
        val page = query.after(from)
        AdminChannelResponses.json(
            request, 200,
            linkedMapOf(
                "items" to page.items.map(AdminChannelResponses::listItem), "nextWatermark" to page.nextWatermark,
                "serverTime" to AdminChannelResponses.time(page.serverTime),
            ),
        )
    }

    private fun read(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        permits.read(endpoint, { AdminChannelResponses.error(request, 503, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            try {
                block()
            } catch (e: OperatorQueryFailure.TimedOut) {
                registry.counter(TIMEOUT_METRIC, "endpoint", endpoint).increment()
                unavailable(request, e)
            } catch (e: RuntimeException) {
                unavailable(request, e)
            }
        }

    private fun unavailable(request: HttpServletRequest, e: RuntimeException): ResponseEntity<ByteArray> {
        log.warn("operation=OperatorDecisionQuery correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", ApiResponses.requestId(request), SafeThrowable.summary(e))
        return AdminChannelResponses.error(request, 503, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
    }

    private companion object {
        val WATERMARK = Regex("^(0|[1-9][0-9]{0,18})$")
        const val TIMEOUT_METRIC = "paycore.payment.admin_channel.query_timeout"
    }
}

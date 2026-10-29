package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.ApiResponses
import com.paycore.payment.payment.adapter.web.SafeThrowable
import com.paycore.payment.payment.application.provided.OperatorCancelTargetQuery
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영자 보정 취소 대상 조회(ADMIN-PAYMENT-OPS-001 §4.3 — 비권위 jOOQ, ADMIN 채널 서명 인증 뒤, ArchUnit 정확 FQN). 형식이 아닌 paymentId도 404 `PAYMENT_NOT_FOUND`(감사 없음 — 조회),
 * 조회 permit 없음 503 `ADMIN_READ_BUSY`, 질의 시간 초과·저장소 실패 503 `TEMPORARILY_UNAVAILABLE`. 카드·기관 정보 없음.
 */
@RestController
@RequestMapping("/internal/v1/admin/payments")
class OperatorCancelTargetController(
    private val query: OperatorCancelTargetQuery,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(OperatorCancelTargetController::class.java)

    @GetMapping("/{paymentId}/operator-cancel-target")
    fun target(@PathVariable paymentId: String, request: HttpServletRequest): ResponseEntity<ByteArray> =
        permits.read(ENDPOINT, { AdminChannelResponses.error(request, 503, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            if (!AdminOperatorCancellationRequestParser.PAYMENT_ID.matches(paymentId)) return@read AdminChannelResponses.error(request, 404, AdminChannelResponses.PAYMENT_NOT_FOUND)
            try {
                val v = query.find(paymentId) ?: return@read AdminChannelResponses.error(request, 404, AdminChannelResponses.PAYMENT_NOT_FOUND)
                AdminChannelResponses.json(
                    request, 200,
                    linkedMapOf(
                        "paymentId" to v.paymentId.value, "paymentStatus" to v.paymentStatus, "cancellable" to v.cancellable, "currency" to v.currency?.value,
                        "authorizedMinor" to v.authorizedMinor, "cancellableMinor" to v.cancellableMinor, "balanceRevision" to v.balanceRevision, "onHold" to v.onHold,
                        "inFlightCancel" to v.inFlightCancel,
                    ),
                )
            } catch (e: OperatorQueryFailure.TimedOut) {
                registry.counter(TIMEOUT_METRIC, "endpoint", ENDPOINT).increment()
                unavailable(request, e)
            } catch (e: RuntimeException) {
                unavailable(request, e)
            }
        }

    private fun unavailable(request: HttpServletRequest, e: RuntimeException): ResponseEntity<ByteArray> {
        log.warn("operation=OperatorCancelTarget correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", ApiResponses.requestId(request), SafeThrowable.summary(e))
        return AdminChannelResponses.error(request, 503, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
    }

    private companion object {
        const val ENDPOINT = "target"
        const val TIMEOUT_METRIC = "paycore.payment.admin_channel.query_timeout"
    }
}

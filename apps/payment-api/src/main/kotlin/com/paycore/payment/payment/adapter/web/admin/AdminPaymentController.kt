package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.ApiResponses
import com.paycore.payment.payment.adapter.web.SafeThrowable
import com.paycore.payment.payment.application.provided.AdminPaymentCursor
import com.paycore.payment.payment.application.provided.AdminPaymentDetail
import com.paycore.payment.payment.application.provided.AdminPaymentListItem
import com.paycore.payment.payment.application.provided.AdminPaymentQuery
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 관리자 거래 조회(TXN-OPS-001 §3 — 비권위 jOOQ, ADMIN 채널 서명 인증 뒤, ArchUnit 정확 FQN). 기존 조회 permit 공용(대기 0 — 없으면 503 `ADMIN_READ_BUSY`), 질의 시간 초과·저장소 실패
 * 503 `TEMPORARILY_UNAVAILABLE`(시간 초과는 `query_timeout{endpoint}`). 검색 본문은 서명된 바이트를 [AdminPaymentSearchRequestParser]로 엄격 해석(실패 400 `REQUEST_MALFORMED`),
 * 형식이 아닌 paymentId는 404 `PAYMENT_NOT_FOUND`. 로그는 연산·결과 분류만(주문명·주문번호·MID·paymentId 없음). 응답에 기관 내부 계정 값·운영자 사유 문구 없음(Query 타입에 없다).
 */
@RestController
@RequestMapping("/internal/v1/admin/")
class AdminPaymentController(
    private val query: AdminPaymentQuery,
    private val permits: AdminChannelPermits,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(AdminPaymentController::class.java)

    @PostMapping("payment-searches")
    fun search(request: HttpServletRequest): ResponseEntity<ByteArray> = read(request, SEARCH) {
        val criteria = AdminPaymentSearchRequestParser.parse(request.inputStream.readAllBytes())
            ?: return@read AdminChannelResponses.error(request, 400, AdminChannelResponses.REQUEST_MALFORMED)
        val page = query.search(criteria)
        AdminChannelResponses.json(request, 200, linkedMapOf("items" to page.items.map(::item), "nextCursor" to page.nextCursor?.let(::cursor)))
    }

    @GetMapping("payments/{paymentId}")
    fun detail(@PathVariable paymentId: String, request: HttpServletRequest): ResponseEntity<ByteArray> = read(request, DETAIL) {
        if (!AdminPaymentSearchRequestParser.PAYMENT_ID.matches(paymentId)) return@read AdminChannelResponses.error(request, 404, AdminChannelResponses.PAYMENT_NOT_FOUND)
        val d = query.detail(paymentId) ?: return@read AdminChannelResponses.error(request, 404, AdminChannelResponses.PAYMENT_NOT_FOUND)
        AdminChannelResponses.json(request, 200, detail(d))
    }

    private fun read(request: HttpServletRequest, endpoint: String, block: () -> ResponseEntity<ByteArray>): ResponseEntity<ByteArray> =
        permits.read(endpoint, { AdminChannelResponses.error(request, 503, AdminChannelResponses.ADMIN_READ_BUSY) }) {
            try {
                block()
            } catch (e: OperatorQueryFailure.TimedOut) {
                registry.counter(TIMEOUT_METRIC, "endpoint", endpoint).increment()
                unavailable(request, endpoint, e)
            } catch (e: RuntimeException) {
                unavailable(request, endpoint, e)
            }
        }

    private fun unavailable(request: HttpServletRequest, endpoint: String, e: RuntimeException): ResponseEntity<ByteArray> {
        log.warn("operation=AdminPaymentQuery endpoint={} correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", endpoint, ApiResponses.requestId(request), SafeThrowable.summary(e))
        return AdminChannelResponses.error(request, 503, AdminChannelResponses.TEMPORARILY_UNAVAILABLE)
    }

    private fun cursor(c: AdminPaymentCursor) = linkedMapOf("createdAt" to AdminChannelResponses.time(c.createdAt), "paymentId" to c.paymentId)

    private fun item(v: AdminPaymentListItem) = linkedMapOf<String, Any?>(
        "paymentId" to v.paymentId, "environment" to v.environment, "merchantAccountId" to v.merchantAccountId, "merchantOrderId" to v.merchantOrderId,
        "orderNamePreview" to v.orderNamePreview, "orderNameTruncated" to v.orderNameTruncated, "amountMinor" to v.amountMinor, "currency" to v.currency,
        "status" to v.status, "createdAt" to AdminChannelResponses.time(v.createdAt),
    )

    private fun detail(d: AdminPaymentDetail): Map<String, Any?> = linkedMapOf(
        "payment" to d.payment.let { p ->
            linkedMapOf(
                "paymentId" to p.paymentId, "environment" to p.environment, "merchantAccountId" to p.merchantAccountId, "merchantSiteId" to p.merchantSiteId,
                "merchantOrderId" to p.merchantOrderId, "orderName" to p.orderName, "amountMinor" to p.amountMinor, "currency" to p.currency,
                "paymentMethod" to p.paymentMethod, "status" to p.status, "createdAt" to AdminChannelResponses.time(p.createdAt),
            )
        },
        "onHold" to d.onHold,
        "terminationEvidence" to d.terminationEvidence,
        "authorization" to d.authorization?.let { a ->
            linkedMapOf(
                "authorizationId" to a.authorizationId, "status" to a.status, "failureEvidence" to a.failureEvidence, "planKind" to a.planKind,
                "installmentMonths" to a.installmentMonths, "cardType" to a.cardType, "openedAt" to AdminChannelResponses.time(a.openedAt),
            )
        },
        "transactions" to d.transactions.map { t ->
            linkedMapOf(
                "transactionId" to t.transactionId, "type" to t.type, "amountMinor" to t.amountMinor, "currency" to t.currency, "planKind" to t.planKind,
                "installmentMonths" to t.installmentMonths, "cardType" to t.cardType, "approvalNumber" to t.approvalNumber, "providerTransactionId" to t.providerTransactionId,
                "cancellationId" to t.cancellationId, "originalTransactionId" to t.originalTransactionId, "cancelScope" to t.cancelScope,
                "institutionTransactionDate" to t.institutionTransactionDate, "institutionBusinessDate" to t.institutionBusinessDate,
                "confirmedAt" to AdminChannelResponses.time(t.confirmedAt), "recordedBy" to t.recordedBy,
            )
        },
        "cancellations" to d.cancellations.map { c ->
            linkedMapOf(
                "cancellationId" to c.cancellationId, "initiatorKind" to c.initiatorKind, "merchantCancelId" to c.merchantCancelId, "scope" to c.scope,
                "status" to c.status, "failureEvidence" to c.failureEvidence, "amountMinor" to c.amountMinor, "currency" to c.currency,
                "openedAt" to AdminChannelResponses.time(c.openedAt),
            )
        },
        "returnBalance" to d.returnBalance?.let { b ->
            linkedMapOf(
                "currency" to b.currency, "authorizedMinor" to b.authorizedMinor, "completedMinor" to b.completedMinor, "reservedMinor" to b.reservedMinor,
                "forcedMinor" to b.forcedMinor, "revision" to b.revision,
            )
        },
    )

    private companion object {
        const val SEARCH = "payment_search"
        const val DETAIL = "payment_detail"
        const val TIMEOUT_METRIC = "paycore.payment.admin_channel.query_timeout"
    }
}

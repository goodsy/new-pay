package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.AuthorizationView
import com.paycore.payment.payment.application.provided.CancellationHistoryItem
import com.paycore.payment.payment.application.provided.CancellationInitiator
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.application.provided.PaymentStatusInconsistent
import com.paycore.payment.payment.application.provided.PaymentStatusQuery
import com.paycore.payment.payment.application.provided.PaymentStatusResult
import com.paycore.payment.payment.application.provided.PaymentStatusView
import com.paycore.payment.payment.application.provided.PaymentViewStatus
import com.paycore.payment.payment.application.provided.ReturnSummaryView
import com.paycore.payment.payment.domain.PaymentPlan
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 결제 현재 상태 조회 공개 API(CP-QUERY-001 §4 — GetPayment, D-CP-003). `GET /v1/payments/{paymentId}`(공개 connector, Bearer — 인증은 필터).
 * - 쿼리 파라미터·본문이 있으면 400(CQ-01). 없음·다른 MID·다른 환경·형식 밖 paymentId는 같은 404 `PAYMENT_NOT_FOUND`(존재 비노출 — §4.3).
 * - 200 본문은 §4.4 필드 집합(승인 terms·반환 요약·취소 이력은 AUTHORIZED일 때만 — D-CPQ-002, `onHold` boolean — D-CPQ-001). 진행 중·확인 중이면 고정 안내(`notice`).
 * - 모든 응답 `Cache-Control: no-store`, ETag 없음(CQ-10). 조회 저장소 장애 503(조회 문구 — CQ-11, `Retry-After`), 스냅샷 불일치(CQ-07)·결함 500.
 * - 결과마다 INFO 한 줄 `operation=GetPayment correlationId keyIdPrefix result`(FOUND만 조회된 paymentId — CQ-09). 제시된 paymentId·요청 값·예외 메시지 원문은 남기지 않는다
 *   (진단 줄은 분류·클래스 이름·규칙 이름만). 지표 `paycore.payment.query{result}`·`paycore.payment.query.duration`(ID label 없음).
 */
@RestController
class GetPaymentController(
    private val query: PaymentStatusQuery,
    private val settings: PaymentApiSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(GetPaymentController::class.java)

    @GetMapping("/v1/payments/{paymentId}")
    fun get(@PathVariable paymentId: String, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val sample = Timer.start(registry)
        try {
            return handle(paymentId, request)
        } finally {
            sample.stop(registry.timer(DURATION_METRIC))
        }
    }

    private fun handle(paymentId: String, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val requestId = ApiResponses.requestId(request)
        val authentication = (SecurityContextHolder.getContext().authentication?.principal as? MerchantAuthentication)
            ?: return error(ApiError(ApiErrorCode.UNAUTHENTICATED), requestId, "UNAUTHENTICATED", null)
        val keyIdPrefix = authentication.credentialKeyId.value.take(KEY_ID_PREFIX)
        return try {
            if (request.queryString != null || request.inputStream.read() != -1) return error(ApiError(ApiErrorCode.INVALID_REQUEST), requestId, "INVALID_REQUEST", keyIdPrefix)
            when (val result = query.find(authentication.environment, authentication.merchantAccountId, paymentId)) {
                is PaymentStatusResult.Found -> {
                    record("FOUND")
                    // 결과 줄(CQ-09): FOUND만 paymentId — 범위 확인을 통과한 자기 Payment 식별자(제시 값이 아니라 조회된 값).
                    log.info("operation=GetPayment correlationId={} keyIdPrefix={} result=FOUND paymentId={}", requestId, keyIdPrefix, result.view.paymentId.value)
                    ResponseEntity.status(HttpStatus.OK)
                        .header(ApiResponses.REQUEST_ID_HEADER, requestId)
                        .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ApiResponses.mapper.writeValueAsBytes(body(result.view)))
                }
                PaymentStatusResult.NotFound -> error(ApiError(ApiErrorCode.PAYMENT_NOT_FOUND), requestId, "NOT_FOUND", keyIdPrefix)
                is PaymentStatusResult.Unavailable -> {
                    // web은 application.required(PersistenceFailure)를 모른다 — provided 결과의 분류만 로그에(ArchUnit webAdapterUsesOnlyApplication).
                    log.warn("operation=GetPayment correlationId={} result=TEMPORARILY_UNAVAILABLE failure={}", requestId, result.reason)
                    error(
                        ApiError(ApiErrorCode.QUERY_TEMPORARILY_UNAVAILABLE), requestId, "UNAVAILABLE", keyIdPrefix,
                        HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, settings.retryAfter.temporarilyUnavailable.seconds.coerceAtLeast(1).toString()) },
                    )
                }
            }
        } catch (e: PaymentStatusInconsistent) {
            log.error("operation=GetPayment correlationId={} result=INTERNAL_ERROR failure=SNAPSHOT_INCONSISTENT rule={}", requestId, e.rule)
            error(ApiError(ApiErrorCode.INTERNAL_ERROR), requestId, "INTERNAL_ERROR", keyIdPrefix)
        } catch (e: RuntimeException) {
            log.error("operation=GetPayment correlationId={} result=INTERNAL_ERROR failure=DEFECT exception={}", requestId, SafeThrowable.summary(e))
            error(ApiError(ApiErrorCode.INTERNAL_ERROR), requestId, "INTERNAL_ERROR", keyIdPrefix)
        }
    }

    private fun body(v: PaymentStatusView): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            "paymentId" to v.paymentId.value,
            "merchantOrderId" to v.merchantOrderId.value,
            "amount" to v.money.amountMinor,
            "currency" to v.money.currency.value,
            "paymentCreatedAt" to CreatePaymentController.formatInstant(v.createdAt),
            "status" to v.status.name,
        )
        v.failureCategory?.let { out["failureCategory"] = it.name }
        out["onHold"] = v.onHold
        out["authorization"] = v.authorization?.let(::authorization)
        out["returnSummary"] = v.returnSummary?.let(::returnSummary)
        out["cancellations"] = v.cancellations.map(::cancellation)
        if (v.status == PaymentViewStatus.IN_PROGRESS || v.status == PaymentViewStatus.UNKNOWN) out["notice"] = NOTICE
        return out
    }

    /** confirm 응답과 같은 승인 필드(AC1 §10.1) — paymentId·notice는 상위에 있다. */
    private fun authorization(a: AuthorizationView): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("authorizationId" to a.authorizationId.value, "status" to a.status.name)
        a.approved?.let { t ->
            out["approvedAmount"] = t.money.amountMinor
            out["currency"] = t.money.currency.value
            out["plan"] = when (val p = t.plan) {
                PaymentPlan.LumpSum -> linkedMapOf("kind" to "LUMP_SUM")
                is PaymentPlan.Installment -> linkedMapOf("kind" to "INSTALLMENT", "months" to p.months)
            }
            out["cardType"] = t.cardType.name
            out["approvalNumber"] = t.approvalNumber
            out["approvedAt"] = CreatePaymentController.formatInstant(checkNotNull(a.approvedAt))
        }
        a.failureCategory?.let { out["failureCategory"] = it.name }
        return out
    }

    private fun returnSummary(s: ReturnSummaryView): Map<String, Any?> = linkedMapOf(
        "authorizedAmount" to s.authorizedMinor,
        "cancelledAmount" to s.cancelledMinor,
        "cancelInProgressAmount" to s.inProgressMinor,
        "cancellableAmount" to s.cancellableMinor,
        "returnStatus" to s.returnStatus.name,
    )

    /** 취소 API 응답과 같은 어휘(CQ-15). 운영자 취소는 개시자 종류만(D-CPQ-002). */
    private fun cancellation(c: CancellationHistoryItem): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("cancellationId" to c.cancellationId.value, "initiator" to c.initiator.name)
        if (c.initiator == CancellationInitiator.MERCHANT) out["merchantCancelId"] = c.merchantCancelId?.value
        out["status"] = c.status.name
        out["amount"] = c.money.amountMinor
        out["currency"] = c.money.currency.value
        out["requestedAt"] = CreatePaymentController.formatInstant(c.requestedAt)
        c.cancelTransactionId?.let { out["cancelTransactionId"] = it }
        c.cancelApprovalNumber?.let { out["cancelApprovalNumber"] = it }
        c.cancelledAt?.let { out["cancelledAt"] = CreatePaymentController.formatInstant(it) }
        c.failureCategory?.let { out["failureCategory"] = it.name }
        return out
    }

    /**
     * 오류 응답 + 결과 줄(CQ-09 — 결과마다 INFO 한 줄: correlationId·keyId 접두·result). 제시된 paymentId는 넣지 않는다(존재 비노출 — NOT_FOUND·INVALID_REQUEST 등).
     * [keyIdPrefix]가 없으면(인증 주체 없음 — 필터가 먼저 401) 결과 줄을 남기지 않는다.
     */
    private fun error(error: ApiError, requestId: String, result: String, keyIdPrefix: String?, headers: HttpHeaders = HttpHeaders()): ResponseEntity<ByteArray> {
        record(result)
        keyIdPrefix?.let { log.info("operation=GetPayment correlationId={} keyIdPrefix={} result={}", requestId, it, result) }
        return ApiResponses.error(error, requestId, headers.apply { set(HttpHeaders.CACHE_CONTROL, NO_STORE) })
    }

    private fun record(result: String) {
        registry.counter(RESULTS_METRIC, "result", result).increment()
    }

    private companion object {
        const val RESULTS_METRIC = "paycore.payment.query"
        const val DURATION_METRIC = "paycore.payment.query.duration"
        const val NO_STORE = "no-store"

        /** 로그의 keyId 접두 길이(CQ-16 — keyId는 비밀이 아니지만 상관에 충분한 앞 6자만). */
        const val KEY_ID_PREFIX = 6
        const val NOTICE = "The payment result is being confirmed. Query this payment again later; do not retry with a new key or create a new payment."
    }
}

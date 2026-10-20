package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.CancelPaymentCommand
import com.paycore.payment.payment.application.provided.CancelPaymentFailure
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.CancelRejection
import com.paycore.payment.payment.application.provided.CancellationView
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 가맹점 취소 공개 API(CANCEL-001 §4.1·§4.9). `POST /v1/payments/{paymentId}/cancel`(공개 connector). 순서: 인증(필터 — 401) → 경로·헤더·본문 형식(400·404) → Use Case → 응답.
 * 확정이면 200(COMPLETED·FAILED), 진행 중이면 202(IN_PROGRESS·UNKNOWN — 새 키·새 merchantCancelId로 재시도 금지 안내, CN-03). 응답에 카드번호·기관 원문·기관 계약계정·내부 버전 없음.
 * 로그에 요청 값·예외 메시지 원문을 남기지 않는다. 지표 `paycore.payment.cancel{result}`(label에 ID 없음).
 */
@RestController
class CancelPaymentController(
    private val cancel: CancelPaymentUseCase,
    private val settings: PaymentApiSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(CancelPaymentController::class.java)

    @PostMapping("/v1/payments/{paymentId}/cancel")
    fun cancel(@PathVariable paymentId: String, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val requestId = ApiResponses.requestId(request)
        val authentication = (SecurityContextHolder.getContext().authentication?.principal as? MerchantAuthentication)
            ?: return error(ApiError(ApiErrorCode.UNAUTHENTICATED), requestId)
        return try {
            if (!ConfirmPaymentRequestParser.isPaymentIdLike(paymentId)) return error(ApiError(ApiErrorCode.PAYMENT_NOT_FOUND), requestId)
            val key = CreatePaymentRequestParser.idempotencyKey(request.getHeaders(CreatePaymentController.IDEMPOTENCY_KEY_HEADER).toList())
            val body = CancelPaymentRequestParser.parse(request.inputStream.readNBytes(CancelPaymentRequestParser.MAX_BODY_BYTES + 1))
            val result = cancel.cancel(
                CancelPaymentCommand(
                    environment = authentication.environment,
                    merchantAccountId = authentication.merchantAccountId,
                    idempotencyKey = key,
                    paymentId = paymentId,
                    merchantCancelId = body.merchantCancelId,
                    money = body.money,
                    correlationId = requestId,
                ),
            )
            respond(result, requestId)
        } catch (e: RequestRejected) {
            error(e.error, requestId)
        } catch (e: CancelPaymentFailure) {
            val (code, retryAfter) = when (e) {
                is CancelPaymentFailure.RequestInProgress -> ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS to settings.retryAfter.requestInProgress
                is CancelPaymentFailure.OutcomeUnknown -> ApiErrorCode.OUTCOME_UNCONFIRMED to settings.retryAfter.temporarilyUnavailable
                is CancelPaymentFailure.TemporarilyUnavailable -> ApiErrorCode.TEMPORARILY_UNAVAILABLE to settings.retryAfter.temporarilyUnavailable
            }
            log.warn("operation=CancelPayment correlationId={} result={} failure={}", requestId, code.wireCode, e.reason)
            record(code.wireCode)
            ApiResponses.error(ApiError(code), requestId, HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, retryAfter.seconds.coerceAtLeast(1).toString()) })
        } catch (e: RuntimeException) {
            record("INTERNAL_ERROR")
            log.error("operation=CancelPayment correlationId={} result=INTERNAL_ERROR failure=DEFECT exception={}", requestId, SafeThrowable.summary(e))
            ApiResponses.error(ApiError(ApiErrorCode.INTERNAL_ERROR), requestId)
        }
    }

    private fun respond(result: CancelPaymentResult, requestId: String): ResponseEntity<ByteArray> = when (result) {
        is CancelPaymentResult.Accepted -> {
            val v = result.view
            val final = v.status == CancellationViewStatus.COMPLETED || v.status == CancellationViewStatus.FAILED
            record(if (result.replayed) "REPLAYED_${v.status}" else v.status.name)
            ResponseEntity.status(if (final) HttpStatus.OK else HttpStatus.ACCEPTED)
                .header(ApiResponses.REQUEST_ID_HEADER, requestId)
                .header(ApiResponses.REPLAYED_HEADER, result.replayed.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponses.mapper.writeValueAsBytes(body(v)))
        }
        is CancelPaymentResult.Rejected -> {
            val code = when (result.rejection) {
                CancelRejection.CANCEL_REQUEST_CONFLICT -> ApiErrorCode.CANCEL_REQUEST_CONFLICT
                CancelRejection.PAYMENT_NOT_CANCELLABLE -> ApiErrorCode.PAYMENT_NOT_CANCELLABLE
                CancelRejection.ALREADY_FULLY_CANCELLED -> ApiErrorCode.ALREADY_FULLY_CANCELLED
                CancelRejection.CANCEL_IN_PROGRESS -> ApiErrorCode.CANCEL_IN_PROGRESS
                CancelRejection.ORDER_ON_HOLD -> ApiErrorCode.ORDER_ON_HOLD
                CancelRejection.CANCEL_AMOUNT_EXCEEDS_BALANCE -> ApiErrorCode.CANCEL_AMOUNT_EXCEEDS_BALANCE
                CancelRejection.CURRENCY_MISMATCH -> ApiErrorCode.CURRENCY_MISMATCH
                CancelRejection.PARTIAL_CANCEL_NOT_SUPPORTED -> ApiErrorCode.PARTIAL_CANCEL_NOT_SUPPORTED
            }
            record(if (result.replayed) "REPLAYED_${code.name}" else code.name)
            ApiResponses.error(
                ApiError(code, referencePaymentId = result.paymentId.value), requestId,
                HttpHeaders().apply { set(ApiResponses.REPLAYED_HEADER, result.replayed.toString()) },
            )
        }
        CancelPaymentResult.NotFound -> error(ApiError(ApiErrorCode.PAYMENT_NOT_FOUND), requestId)
        CancelPaymentResult.IdempotencyKeyReused -> error(ApiError(ApiErrorCode.IDEMPOTENCY_KEY_REUSED), requestId)
    }

    private fun body(v: CancellationView): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            "paymentId" to v.paymentId.value, "cancellationId" to v.cancellationId.value, "status" to v.status.name,
            "amount" to v.money.amountMinor, "currency" to v.money.currency.value,
        )
        if (v.status == CancellationViewStatus.COMPLETED) {
            out["cancelTransactionId"] = v.cancelTransactionId
            v.cancelApprovalNumber?.let { out["cancelApprovalNumber"] = it }
            v.cancelledAt?.let { out["cancelledAt"] = CreatePaymentController.formatInstant(it) }
        }
        v.failureCategory?.let { out["failureCategory"] = it.name }
        out["cancellableAmount"] = v.cancellableAmountMinor
        out["returnStatus"] = v.returnStatus.name
        if (v.status == CancellationViewStatus.IN_PROGRESS || v.status == CancellationViewStatus.UNKNOWN) {
            out["notice"] = "The cancel result is being confirmed. Query again with the same Idempotency-Key; do not retry with a new key or a new merchantCancelId."
        }
        return out
    }

    private fun error(error: ApiError, requestId: String): ResponseEntity<ByteArray> {
        record(error.code.wireCode)
        return ApiResponses.error(error, requestId)
    }

    private fun record(result: String) {
        registry.counter(RESULTS_METRIC, "result", result).increment()
    }

    private companion object {
        const val RESULTS_METRIC = "paycore.payment.cancel"
    }
}

package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.AuthorizationView
import com.paycore.payment.payment.application.provided.AuthorizationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentCommand
import com.paycore.payment.payment.application.provided.ConfirmPaymentFailure
import com.paycore.payment.payment.application.provided.ConfirmPaymentResult
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmRejection
import com.paycore.payment.payment.application.provided.MerchantAuthentication
import com.paycore.payment.payment.domain.PaymentPlan
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
 * confirm 공개 API(AUTH-001 §4.1·§4.9). `POST /v1/payments/{paymentId}/confirm`(공개 connector). 순서: 인증(필터 — 401) → 경로·헤더·본문 형식(400·404) → Use Case → 응답.
 * 확정이면 200(AUTHORIZED·FAILED), 진행 중이면 202(IN_PROGRESS·UNKNOWN — 새 키·새 Payment로 재시도 금지 안내). 응답에 카드번호·인증 증거·기관 원문·기관 계약계정·내부 버전 없음.
 * 로그에 요청 값·예외 메시지 원문을 남기지 않는다. 지표 `paycore.payment.confirm{result}`(label에 ID 없음).
 */
@RestController
class ConfirmPaymentController(
    private val confirm: ConfirmPaymentUseCase,
    private val settings: PaymentApiSettings,
    private val registry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(ConfirmPaymentController::class.java)

    @PostMapping("/v1/payments/{paymentId}/confirm")
    fun confirm(@PathVariable paymentId: String, request: HttpServletRequest): ResponseEntity<ByteArray> {
        val requestId = ApiResponses.requestId(request)
        val authentication = (SecurityContextHolder.getContext().authentication?.principal as? MerchantAuthentication)
            ?: return error(ApiError(ApiErrorCode.UNAUTHENTICATED), requestId)
        return try {
            if (!ConfirmPaymentRequestParser.isPaymentIdLike(paymentId)) return error(ApiError(ApiErrorCode.PAYMENT_NOT_FOUND), requestId)
            val key = CreatePaymentRequestParser.idempotencyKey(request.getHeaders(CreatePaymentController.IDEMPOTENCY_KEY_HEADER).toList())
            val body = ConfirmPaymentRequestParser.parse(request.inputStream.readNBytes(ConfirmPaymentRequestParser.MAX_BODY_BYTES + 1))
            val result = confirm.confirm(
                ConfirmPaymentCommand(
                    environment = authentication.environment,
                    merchantAccountId = authentication.merchantAccountId,
                    idempotencyKey = key,
                    paymentId = paymentId,
                    authenticationRef = body.authenticationRef,
                    merchantOrderId = body.merchantOrderId,
                    money = body.money,
                    correlationId = requestId,
                ),
            )
            respond(result, requestId)
        } catch (e: RequestRejected) {
            error(e.error, requestId)
        } catch (e: ConfirmPaymentFailure) {
            val (code, retryAfter) = when (e) {
                is ConfirmPaymentFailure.RequestInProgress -> ApiErrorCode.IDEMPOTENCY_REQUEST_IN_PROGRESS to settings.retryAfter.requestInProgress
                is ConfirmPaymentFailure.OutcomeUnknown -> ApiErrorCode.OUTCOME_UNCONFIRMED to settings.retryAfter.temporarilyUnavailable
                is ConfirmPaymentFailure.TemporarilyUnavailable -> ApiErrorCode.TEMPORARILY_UNAVAILABLE to settings.retryAfter.temporarilyUnavailable
            }
            log.warn("operation=ConfirmPayment correlationId={} result={} failure={}", requestId, code.wireCode, e.reason)
            record(code.wireCode)
            ApiResponses.error(ApiError(code), requestId, HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, retryAfter.seconds.coerceAtLeast(1).toString()) })
        } catch (e: RuntimeException) {
            record("INTERNAL_ERROR")
            log.error("operation=ConfirmPayment correlationId={} result=INTERNAL_ERROR failure=DEFECT exception={}", requestId, SafeThrowable.summary(e))
            ApiResponses.error(ApiError(ApiErrorCode.INTERNAL_ERROR), requestId)
        }
    }

    private fun respond(result: ConfirmPaymentResult, requestId: String): ResponseEntity<ByteArray> = when (result) {
        is ConfirmPaymentResult.Accepted -> {
            val v = result.view
            val final = v.status == AuthorizationViewStatus.AUTHORIZED || v.status == AuthorizationViewStatus.FAILED
            record(if (result.replayed) "REPLAYED_${v.status}" else v.status.name)
            ResponseEntity.status(if (final) HttpStatus.OK else HttpStatus.ACCEPTED)
                .header(ApiResponses.REQUEST_ID_HEADER, requestId)
                .header(ApiResponses.REPLAYED_HEADER, result.replayed.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponses.mapper.writeValueAsBytes(body(v)))
        }
        is ConfirmPaymentResult.Rejected -> {
            val code = when (result.rejection) {
                ConfirmRejection.CONFIRM_CONTENT_CONFLICT -> ApiErrorCode.CONFIRM_CONTENT_CONFLICT
                ConfirmRejection.AUTHENTICATION_NOT_VERIFIED -> ApiErrorCode.AUTHENTICATION_NOT_VERIFIED
                ConfirmRejection.PAYMENT_PLAN_NOT_ALLOWED -> ApiErrorCode.PAYMENT_PLAN_NOT_ALLOWED
                ConfirmRejection.PAYMENT_NOT_ELIGIBLE -> ApiErrorCode.PAYMENT_NOT_ELIGIBLE
                ConfirmRejection.LIMIT_EXCEEDED -> ApiErrorCode.LIMIT_EXCEEDED
                ConfirmRejection.INCIDENT_HOLD -> ApiErrorCode.ORDER_ON_HOLD
                ConfirmRejection.AUTHORIZED_NEW_ORDER_REQUIRED -> ApiErrorCode.ORDER_ALREADY_AUTHORIZED
                ConfirmRejection.PAYMENT_TERMINATED -> ApiErrorCode.PAYMENT_TERMINATED
            }
            record(if (result.replayed) "REPLAYED_${code.name}" else code.name)
            ApiResponses.error(
                ApiError(code, reason = result.detail, referencePaymentId = result.paymentId.value), requestId,
                HttpHeaders().apply { set(ApiResponses.REPLAYED_HEADER, result.replayed.toString()) },
            )
        }
        ConfirmPaymentResult.NotFound -> error(ApiError(ApiErrorCode.PAYMENT_NOT_FOUND), requestId)
        ConfirmPaymentResult.IdempotencyKeyReused -> error(ApiError(ApiErrorCode.IDEMPOTENCY_KEY_REUSED), requestId)
    }

    private fun body(v: AuthorizationView): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("paymentId" to v.paymentId.value, "authorizationId" to v.authorizationId.value, "status" to v.status.name)
        v.approved?.let { t ->
            out["approvedAmount"] = t.money.amountMinor
            out["currency"] = t.money.currency.value
            out["plan"] = when (val p = t.plan) {
                PaymentPlan.LumpSum -> linkedMapOf("kind" to "LUMP_SUM")
                is PaymentPlan.Installment -> linkedMapOf("kind" to "INSTALLMENT", "months" to p.months)
            }
            out["cardType"] = t.cardType.name
            out["approvalNumber"] = t.approvalNumber
            out["approvedAt"] = CreatePaymentController.formatInstant(checkNotNull(v.approvedAt))
        }
        v.failureCategory?.let { out["failureCategory"] = it.name }
        if (v.status == AuthorizationViewStatus.IN_PROGRESS || v.status == AuthorizationViewStatus.UNKNOWN) {
            out["notice"] = "The result is being confirmed. Query again with the same Idempotency-Key; do not retry with a new key or a new payment."
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
        const val RESULTS_METRIC = "paycore.payment.confirm"
    }
}

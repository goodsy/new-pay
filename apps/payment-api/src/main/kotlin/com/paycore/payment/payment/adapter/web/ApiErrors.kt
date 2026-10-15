package com.paycore.payment.payment.adapter.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * 공개 API 오류 code(CP-CONTRACT-001 §9 + CP-API-001 명세 §7.2의 라우팅·결함 code). 문구는 code별 **고정** 문장이다 — 요청 값·내부 값을
 * 넣지 않는다(§9.1). [retryable]은 같은 Idempotency-Key 재시도가 결과를 바꿀 수 있는가다(503·IN_PROGRESS만 true).
 */
enum class ApiErrorCode(val status: HttpStatus, val retryable: Boolean, val message: String) {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, false, "The request is malformed or violates the request schema."),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, false, "amount must be a JSON integer in currency minor units, at least 1."),
    INVALID_CURRENCY(HttpStatus.BAD_REQUEST, false, "currency must be an ISO 4217 alphabetic code in upper case."),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, false, "Exactly one Idempotency-Key header of 1..64 characters [A-Za-z0-9-_.:] is required."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, false, "Authentication is required."),
    MERCHANT_SCOPE_MISMATCH(HttpStatus.FORBIDDEN, false, "The request is outside the authenticated merchant scope."),
    MERCHANT_SITE_NOT_FOUND(HttpStatus.NOT_FOUND, false, "The merchant site was not found."),
    PAYMENT_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, false, "The payment is not eligible for this merchant site."),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, false, "The Idempotency-Key was already used with a different request."),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, true, "A request with the same Idempotency-Key is in progress. Retry with the same key."),
    ORDER_INTENT_CONFLICT(HttpStatus.CONFLICT, false, "The order already exists with different fixed inputs. Use a new merchantOrderId."),
    ORDER_ON_HOLD(HttpStatus.CONFLICT, false, "The order is on hold for investigation."),
    ORDER_ALREADY_AUTHORIZED(HttpStatus.CONFLICT, false, "The order was already authorized. Use a new merchantOrderId for a new purchase."),
    ORDER_RESULT_UNCONFIRMED(HttpStatus.CONFLICT, false, "The previous payment result of the order is not confirmed yet."),
    ELIGIBILITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Payment eligibility is temporarily unavailable. Retry with the same Idempotency-Key."),
    TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "The service is temporarily unavailable. Retry with the same Idempotency-Key."),
    OUTCOME_UNCONFIRMED(HttpStatus.SERVICE_UNAVAILABLE, true, "The result could not be confirmed. Retry with the same Idempotency-Key to obtain it; do not use a new key."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false, "An internal error occurred."),
    NOT_FOUND(HttpStatus.NOT_FOUND, false, "The resource was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false, "The method is not allowed."),
    ;

    /** 응답 `code` 값. OUTCOME_UNCONFIRMED는 계약 §9 행대로 `TEMPORARILY_UNAVAILABLE` code를 쓴다(문구만 다름). */
    val wireCode: String get() = if (this == OUTCOME_UNCONFIRMED) TEMPORARILY_UNAVAILABLE.name else name
}

/** 오류 응답 하나(필드는 계약 §9 관례 + 명세 §7.1). */
data class ApiError(
    val code: ApiErrorCode,
    val field: String? = null,
    val reason: String? = null,
    val referencePaymentId: String? = null,
)

/** 요청 상관 ID와 JSON 응답 작성(필터·컨트롤러 공통). */
object ApiResponses {
    const val REQUEST_ID_HEADER = "PayCore-Request-Id"
    const val REPLAYED_HEADER = "Idempotent-Replayed"

    /** 로그 MDC 키(요청 상관 ID). entitlement 모듈의 같은 이름 상수와 값이 같아야 한다(시험이 HTTP→entitlement 로그로 확인). */
    const val CORRELATION_ID_MDC_KEY = "correlationId"
    private const val REQUEST_ID_ATTRIBUTE = "paycore.requestId"

    val mapper: JsonMapper = JsonMapper.builder().build()

    /** 이 요청의 서버 생성 requestId(진입 필터가 정한다. 필터를 거치지 않은 경로에서는 여기서 만든다). */
    fun requestId(request: HttpServletRequest): String =
        (request.getAttribute(REQUEST_ID_ATTRIBUTE) as? String) ?: UUID.randomUUID().toString().also {
            request.setAttribute(REQUEST_ID_ATTRIBUTE, it)
        }

    fun errorBody(error: ApiError, requestId: String): ByteArray {
        val body = linkedMapOf<String, Any>(
            "code" to error.code.wireCode,
            "message" to error.code.message,
            "retryable" to error.code.retryable,
            "requestId" to requestId,
        )
        error.field?.let { body["field"] = it }
        error.reason?.let { body["reason"] = it }
        error.referencePaymentId?.let { body["referencePaymentId"] = it }
        return mapper.writeValueAsBytes(mapOf("error" to body))
    }

    fun error(error: ApiError, requestId: String, headers: HttpHeaders = HttpHeaders()): ResponseEntity<ByteArray> =
        ResponseEntity.status(error.code.status).headers(headers).header(REQUEST_ID_HEADER, requestId)
            .contentType(MediaType.APPLICATION_JSON).body(errorBody(error, requestId))

    /** 필터 단계(컨트롤러 밖)에서 오류를 쓴다. */
    fun write(response: HttpServletResponse, error: ApiError, requestId: String, extraHeaders: Map<String, String> = emptyMap()) {
        response.status = error.code.status.value()
        response.setHeader(REQUEST_ID_HEADER, requestId)
        extraHeaders.forEach(response::setHeader)
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.outputStream.write(errorBody(error, requestId))
    }
}

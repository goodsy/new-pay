package com.paycore.payment.payment.adapter.web

import org.springframework.http.HttpStatus

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

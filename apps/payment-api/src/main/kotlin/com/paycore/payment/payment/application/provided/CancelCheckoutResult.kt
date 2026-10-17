package com.paycore.payment.payment.application.provided

/** 결제창 취소 결과(AUTHN-001 §6.1·§6.3). */
sealed interface CancelCheckoutResult {
    /** 303 failUrl(code=USER_CANCELLED) — 비종료 인증 세션 FAILED(USER_CANCELLED), 결제창 세션 COMPLETED(CANCELLED), 쿠키 삭제. */
    data class Cancelled(val failRedirect: String) : CancelCheckoutResult

    /** 401 — 쿠키 없음·불일치·결제창 세션 종료·만료. */
    data object SessionExpired : CancelCheckoutResult

    /** 409 — Payment가 REQUESTED가 아니다(결제창 세션 COMPLETED(PAYMENT_NOT_AVAILABLE)). */
    data class PaymentNotAvailable(val failRedirect: String) : CancelCheckoutResult

    /** 410 — 결제창 시각 경과(자기 세션·결제창 세션 EXPIRED(PAYMENT_CLOSED), Payment 불변). */
    data class CheckoutTimeOver(val failRedirect: String) : CancelCheckoutResult

    /** 503 — 저장소 일시 실패. */
    data object Unavailable : CancelCheckoutResult
}

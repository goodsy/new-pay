package com.paycore.payment.payment.application.provided

/** 결제창 화면 결과(AUTHN-001 §6.3 화면 오류 매핑). */
sealed interface CheckoutPageResult {
    data class Page(val view: CheckoutPageView) : CheckoutPageResult

    /** handle 형식 오류 — 쿠키·DB를 보지 않는다(400). */
    data object BadHandle : CheckoutPageResult

    /** 쿠키 없음·불일치·세션 없음·REVOKED·EXPIRED·COMPLETED·만료 시각 경과(401, 쿠키 삭제). 읽기 경로라 상태를 바꾸지 않는다. */
    data object SessionExpired : CheckoutPageResult

    /** Payment가 REQUESTED가 아니다(409) — 결제창 세션 COMPLETED(PAYMENT_NOT_AVAILABLE), 쿠키 삭제, failUrl 링크. */
    data class NotAvailable(val code: CheckoutFailCode, val failRedirect: String) : CheckoutPageResult

    /** 저장소 일시 실패(503 화면). */
    data object Unavailable : CheckoutPageResult
}

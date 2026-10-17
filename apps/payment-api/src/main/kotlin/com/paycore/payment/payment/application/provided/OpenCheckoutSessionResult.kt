package com.paycore.payment.payment.application.provided

import java.time.Instant

/** 결제창 토큰 교환 결과(AUTHN-001 §6.1·§6.3). */
sealed interface OpenCheckoutSessionResult {
    /**
     * 커밋된 새 결제창 세션. [cookieSecret]은 쿠키 `__Host-pc_checkout_<handle>`에만 쓴다(DB에는 digest만). [maxAgeSeconds] = 세션 남은 초(내림, 최소 1 — §12.2).
     */
    data class Opened(val handle: String, val cookieSecret: String, val expiresAt: Instant, val maxAgeSeconds: Long) : OpenCheckoutSessionResult {
        override fun toString(): String = "Opened(expiresAt=$expiresAt)"
    }

    /** 토큰 없음·형식 오류·사용됨·대체됨·만료·닫힘 — 사유를 구분하지 않는다(400, 토큰 열거 방지). */
    data object InvalidToken : OpenCheckoutSessionResult

    /** Payment가 REQUESTED가 아니거나 결제창 시각이 지났다(409) — 토큰 CLOSED, 결제창 세션 없음, Payment 불변. [failRedirect] = failUrl + paymentId·code. */
    data class NotAvailable(val code: CheckoutFailCode, val failRedirect: String) : OpenCheckoutSessionResult

    /** 저장소 일시 실패·commit 불명확 — 상태를 알 수 없어 "다시 시도" 화면(토큰은 재사용될 수 없으면 가맹점 재발급). */
    data object Unavailable : OpenCheckoutSessionResult
}

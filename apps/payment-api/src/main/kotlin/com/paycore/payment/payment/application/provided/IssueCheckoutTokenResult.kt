package com.paycore.payment.payment.application.provided

import java.time.Instant

/** 결제창 토큰 발급 결과(AUTHN-001 §5.3). */
sealed interface IssueCheckoutTokenResult {
    /** 커밋된 발급. [checkoutUrl]은 설정 origin + `/checkout?token=<원문>` — 원문은 이 응답에만 있다(DB에는 digest만, D-AUTHN-001). */
    data class Issued(val paymentId: String, val checkoutUrl: String, val expiresAt: Instant) : IssueCheckoutTokenResult {
        override fun toString(): String = "Issued(paymentId=$paymentId, expiresAt=$expiresAt)"
    }

    /** [field]는 INVALID_RETURN_URL일 때 `successUrl`·`failUrl`. */
    data class Rejected(val reason: CheckoutTokenRejection, val field: String? = null) : IssueCheckoutTokenResult
}

package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.PaymentId

/** confirm 결과(AUTH-001 §4.9). [Accepted.view]는 응답 시점의 승인 **현재** 상태(D-AUTH-004 — 재생도 현재 상태). */
sealed interface ConfirmPaymentResult {
    data class Accepted(val view: AuthorizationView, val replayed: Boolean) : ConfirmPaymentResult

    data class Rejected(val rejection: ConfirmRejection, val paymentId: PaymentId, val detail: String?, val replayed: Boolean) : ConfirmPaymentResult

    data object NotFound : ConfirmPaymentResult

    data object IdempotencyKeyReused : ConfirmPaymentResult
}

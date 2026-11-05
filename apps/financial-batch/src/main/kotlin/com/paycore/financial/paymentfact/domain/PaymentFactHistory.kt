package com.paycore.financial.paymentfact.domain

/** 한 Payment에 이미 반영한 사실(보류 포함). [owner] = 결제별 직렬화 행에 처음 기록한 신원(첫 사실이면 null). */
data class PaymentFactHistory(
    val owner: PaymentFactIdentity?,
    val authorizations: List<AuthorizedPaymentFact>,
    val cancellations: List<CancelledPaymentFact>,
) {
    companion object {
        val EMPTY = PaymentFactHistory(null, emptyList(), emptyList())
    }
}

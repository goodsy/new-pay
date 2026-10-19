package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.Money

/**
 * 검증된 confirm 입력(AUTH-001 §4.1). [environment]·[merchantAccountId]는 인증 Credential에서 온다(가맹점 입력 아님). [paymentId]는 경로 원문(형식은 web이 거르고 없으면
 * 404), [authenticationRef]·[merchantOrderId]·[money]는 본문 재진술이다(서버가 Payment 생성 불변 값과 대조 — CA-08). [correlationId]는 서버 requestId.
 */
data class ConfirmPaymentCommand(
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val idempotencyKey: IdempotencyKey,
    val paymentId: String,
    val authenticationRef: String,
    val merchantOrderId: String,
    val money: Money,
    val correlationId: String,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION_ID && correlationId.all { it in '!'..'~' }) {
            "correlationId must be 1..$MAX_CORRELATION_ID printable ASCII characters"
        }
    }

    override fun toString(): String = "ConfirmPaymentCommand(environment=$environment, paymentId=$paymentId, correlationId=$correlationId)"

    private companion object {
        const val MAX_CORRELATION_ID = 128
    }
}

package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.Money

/**
 * 검증된 가맹점 취소 입력(CANCEL-001 §4.1 CN-01). [environment]·[merchantAccountId]는 인증 Credential에서 온다(가맹점 입력 아님). [paymentId]는 경로 원문(형식이 아니거나 없으면 404),
 * [merchantCancelId]는 (Payment, merchantCancelId) 업무 효과 키(D-CANCEL-002), [money]는 취소 금액·통화. [correlationId]는 서버 requestId. 가맹점 사유 문구는 받지 않는다.
 */
data class CancelPaymentCommand(
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val idempotencyKey: IdempotencyKey,
    val paymentId: String,
    val merchantCancelId: MerchantCancelId,
    val money: Money,
    val correlationId: String,
) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION_ID && correlationId.all { it in '!'..'~' }) {
            "correlationId must be 1..$MAX_CORRELATION_ID printable ASCII characters"
        }
    }

    override fun toString(): String = "CancelPaymentCommand(environment=$environment, paymentId=$paymentId, correlationId=$correlationId)"

    private companion object {
        const val MAX_CORRELATION_ID = 128
    }
}

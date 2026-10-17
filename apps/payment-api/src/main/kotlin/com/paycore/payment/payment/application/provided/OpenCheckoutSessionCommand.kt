package com.paycore.payment.payment.application.provided

/** 결제창 토큰 교환 입력(AUTHN-001 §6.1 `GET /checkout?token=…`). [token]은 원문 그대로 — 형식 판정은 Use Case가 한다(형식 오류도 같은 거절, 열거 방지). */
data class OpenCheckoutSessionCommand(val token: String, val correlationId: String) {
    init {
        require(correlationId.length in 1..MAX_CORRELATION_ID && correlationId.all { it in '!'..'~' }) { "correlationId must be 1..$MAX_CORRELATION_ID printable ASCII" }
    }

    override fun toString(): String = "OpenCheckoutSessionCommand(correlationId=$correlationId)"

    private companion object {
        const val MAX_CORRELATION_ID = 64
    }
}

package com.paycore.payment.payment.application.provided

/** 결제창 취소 입력(AUTHN-001 §6.1 `POST /checkout/s/{handle}/cancel`). */
data class CancelCheckoutCommand(val handle: String, val cookieSecret: String?, val correlationId: String) {
    override fun toString(): String = "CancelCheckoutCommand(correlationId=$correlationId)"
}

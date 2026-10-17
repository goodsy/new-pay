package com.paycore.payment.payment.application.provided

/** 결제창 화면 요청: 경로 handle 원문과 그 handle 쿠키의 비밀(없으면 null). */
data class CheckoutPageRequest(val handle: String, val cookieSecret: String?, val correlationId: String) {
    override fun toString(): String = "CheckoutPageRequest(correlationId=$correlationId)"
}

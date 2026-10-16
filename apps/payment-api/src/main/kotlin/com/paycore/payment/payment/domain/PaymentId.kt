package com.paycore.payment.payment.domain

/** 서버가 생성하는 Payment 식별자(생성은 application의 Port 책임). */
@JvmInline
value class PaymentId(val value: String) {
    init {
        require(value.isNotEmpty()) { "paymentId must not be empty" }
    }
}

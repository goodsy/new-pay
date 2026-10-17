package com.paycore.payment.payment.domain

/** 기관이 echo한 바인딩(D-AUTHN-003: paymentId·금액·통화·MID·환경·Plan·카드사). */
data class ProviderBindingEcho(
    val paymentId: PaymentId,
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val money: Money,
    val plan: PaymentPlan,
    val cardCompany: String,
)

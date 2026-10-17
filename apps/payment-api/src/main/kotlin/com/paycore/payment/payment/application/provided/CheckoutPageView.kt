package com.paycore.payment.payment.application.provided

/** 결제창 화면 DTO(Entity 아님 — OSIV off, 관례 §11.1). 값은 표시 전용이다(판정은 POST 잠금 TX가 다시 한다). */
data class CheckoutPageView(
    val handle: String,
    val orderName: String?,
    val amountDisplay: String,
    val currency: String,
    val siteHost: String,
    val cardCompanies: List<CheckoutChoice>,
    val plans: List<CheckoutChoice>,
    /** CSP `form-action`에 넣을 origin(설정 Simulator 고객 origin + 이 결제창 세션의 Site origin, AN-18). */
    val formActionOrigins: List<String>,
)

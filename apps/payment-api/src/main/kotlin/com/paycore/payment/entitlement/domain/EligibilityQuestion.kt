package com.paycore.payment.entitlement.domain

/** 판정 요청(CreatePayment eligibility 입력 중 판정에 쓰는 값). environment는 판정에 쓰지 않는다(D-MS-001 — TEST도 LIVE와 같은 조건). */
data class EligibilityQuestion(val mid: String, val siteId: String, val paymentMethod: String, val currency: String)

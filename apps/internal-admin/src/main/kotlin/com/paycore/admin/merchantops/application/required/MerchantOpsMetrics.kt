package com.paycore.admin.merchantops.application.required

/** 가맹점 조회 지표 Port(MERCHANT-OPS-001 §5.5 — 구현 Micrometer `paycore.admin.merchant_ops.rate_limited{kind}`). label은 고정 분류(`LOOKUP`·`REGISTRATION_NUMBER`)뿐. */
interface MerchantOpsMetrics {
    fun rateLimited(kind: String)
}

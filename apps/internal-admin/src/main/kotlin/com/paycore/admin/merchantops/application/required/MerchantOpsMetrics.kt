package com.paycore.admin.merchantops.application.required

/** 가맹점 조회 지표 Port(MERCHANT-OPS-001 §5.5 — 구현 Micrometer `paycore.admin.merchant_ops.rate_limited{kind}`). label은 고정 분류(`LOOKUP`·`REGISTRATION_NUMBER`)뿐. */
interface MerchantOpsMetrics {
    fun rateLimited(kind: String)

    /** 등록 명령 결과(MERCHANT-OPS-002 §7 — `paycore.admin.merchant_ops.command{operation,result}`). label은 고정 code뿐(ID·값 없음). */
    fun command(operation: String, result: String)

    /** 결과 불명(Unknown·RetrySameKey — `paycore.admin.merchant_ops.command_unknown{operation}`, 경보 후보). */
    fun commandUnknown(operation: String)
}

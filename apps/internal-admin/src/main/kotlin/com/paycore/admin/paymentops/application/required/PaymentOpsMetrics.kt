package com.paycore.admin.paymentops.application.required

/**
 * PAYMENT 호출 지표 Port(ADMIN-PAYMENT-OPS-001 §6 — 구현 `adapter.integration.MicrometerPaymentOpsMetrics`). label은 고정 분류값만(paymentId·승인 ID 없음).
 * - [call]: `paycore.admin.ops.call{operation,result}` — operation `TARGET`·`EXECUTE`, result `FOUND`·`NOT_FOUND`·`UNAVAILABLE`·`STARTED`·`OWNER_REJECTED`·`UNKNOWN`.
 * - [lookupRateLimited]: `paycore.admin.ops.lookup_rate_limited` — 화면 조회 속도 제한(429).
 */
interface PaymentOpsMetrics {
    fun call(operation: String, result: String)

    fun lookupRateLimited()
}

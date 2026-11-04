package com.paycore.admin.paymentops.application.required

/** 거래 조회 지표 Port(TXN-OPS-001 §5.2 — 구현 Micrometer `paycore.admin.ops.transaction_rate_limited`). label 없음(결제 ID·actor 없음 — 07-operations). */
interface TransactionOpsMetrics {
    fun rateLimited()
}

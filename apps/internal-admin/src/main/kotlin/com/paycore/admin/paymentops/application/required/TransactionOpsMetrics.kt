package com.paycore.admin.paymentops.application.required

/** 거래 조회 지표 Port(TXN-OPS-001 §5.2 — 구현 Micrometer `paycore.admin.ops.transaction_rate_limited`). label 없음(결제 ID·actor 없음 — 07-operations). */
interface TransactionOpsMetrics {
    fun rateLimited()

    /** TXN-LIST-ENRICH-001 §8: 목록·상세 라벨 결과 분류(OK·SKIPPED·BUSY·UNAVAILABLE·UNREADABLE — MID·상호·코드 없음). */
    fun merchantLabels(result: String)
}

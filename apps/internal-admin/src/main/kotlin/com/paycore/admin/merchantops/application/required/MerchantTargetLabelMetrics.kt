package com.paycore.admin.merchantops.application.required

/**
 * 화면 가맹점 라벨 API 지표 Port(MERCHANT-CODE-001 §5.6.5 Observability — 구현 Micrometer `paycore.admin.ops.merchant_target_labels{result}`). label 값은 고정 분류
 * (OK·SKIPPED·BUSY·UNAVAILABLE·UNREADABLE — TXN-LIST-ENRICH-001 §8 분류 그대로 + RATE_LIMITED(C-35))뿐 — 키·상호·코드·actor 없음.
 */
interface MerchantTargetLabelMetrics {
    fun targetLabels(result: String)
}

package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.required.TransactionOpsMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * [TransactionOpsMetrics] Micrometer 구현: `paycore.admin.ops.transaction_rate_limited`(label 없음), `paycore.admin.ops.transaction_merchant_labels{result}`(TXN-LIST-ENRICH-001 §8 —
 * label 값은 분류 5개뿐).
 */
@Component
class MicrometerTransactionOpsMetrics(private val registry: MeterRegistry) : TransactionOpsMetrics {
    override fun rateLimited() {
        registry.counter(RATE_LIMITED).increment()
    }

    override fun merchantLabels(result: String) {
        require(result in LABEL_RESULTS) { "label result class" }
        registry.counter(MERCHANT_LABELS, "result", result).increment()
    }

    companion object {
        const val RATE_LIMITED = "paycore.admin.ops.transaction_rate_limited"
        const val MERCHANT_LABELS = "paycore.admin.ops.transaction_merchant_labels"
        private val LABEL_RESULTS = setOf("OK", "SKIPPED", "BUSY", "UNAVAILABLE", "UNREADABLE")
    }
}

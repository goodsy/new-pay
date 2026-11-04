package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.required.TransactionOpsMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** [TransactionOpsMetrics] Micrometer 구현: `paycore.admin.ops.transaction_rate_limited`(label 없음). */
@Component
class MicrometerTransactionOpsMetrics(private val registry: MeterRegistry) : TransactionOpsMetrics {
    override fun rateLimited() {
        registry.counter(RATE_LIMITED).increment()
    }

    companion object {
        const val RATE_LIMITED = "paycore.admin.ops.transaction_rate_limited"
    }
}

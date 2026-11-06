package com.paycore.admin.financialops.adapter.integration

import com.paycore.admin.financialops.application.required.FinancialOpsMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** [FinancialOpsMetrics] Micrometer 구현: `paycore.admin.financial_ops.rate_limited`(label 없음 — 값·actor 없음). */
@Component
class MicrometerFinancialOpsMetrics(private val registry: MeterRegistry) : FinancialOpsMetrics {
    override fun rateLimited() {
        registry.counter(RATE_LIMITED).increment()
    }

    companion object {
        const val RATE_LIMITED = "paycore.admin.financial_ops.rate_limited"
    }
}

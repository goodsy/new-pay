package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.required.PaymentOpsMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** PAYMENT 호출 지표(Micrometer — ADMIN-PAYMENT-OPS-001 §6). */
@Component
class MicrometerPaymentOpsMetrics(private val registry: MeterRegistry) : PaymentOpsMetrics {
    override fun call(operation: String, result: String) {
        registry.counter(CALL, "operation", operation, "result", result).increment()
    }

    override fun lookupRateLimited() {
        registry.counter(LOOKUP_RATE_LIMITED).increment()
    }

    companion object {
        const val CALL = "paycore.admin.ops.call"
        const val LOOKUP_RATE_LIMITED = "paycore.admin.ops.lookup_rate_limited"
    }
}

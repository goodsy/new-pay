package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * [MerchantOpsMetrics] Micrometer 구현: `paycore.admin.merchant_ops.rate_limited{kind}`(kind = LOOKUP·REGISTRATION_NUMBER·REGISTRATION — 값·actor 없음),
 * `…command{operation,result}`·`…command_unknown{operation}`(MERCHANT-OPS-002 §7).
 */
@Component
class MicrometerMerchantOpsMetrics(private val registry: MeterRegistry) : MerchantOpsMetrics {
    override fun rateLimited(kind: String) {
        registry.counter(RATE_LIMITED, "kind", kind).increment()
    }

    override fun command(operation: String, result: String) {
        registry.counter(COMMAND, "operation", operation, "result", result).increment()
    }

    override fun commandUnknown(operation: String) {
        registry.counter(COMMAND_UNKNOWN, "operation", operation).increment()
    }

    companion object {
        const val RATE_LIMITED = "paycore.admin.merchant_ops.rate_limited"
        const val COMMAND = "paycore.admin.merchant_ops.command"
        const val COMMAND_UNKNOWN = "paycore.admin.merchant_ops.command_unknown"
    }
}

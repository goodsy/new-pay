package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** [MerchantOpsMetrics] Micrometer 구현: `paycore.admin.merchant_ops.rate_limited{kind}`(kind = LOOKUP·REGISTRATION_NUMBER — 값·actor 없음). */
@Component
class MicrometerMerchantOpsMetrics(private val registry: MeterRegistry) : MerchantOpsMetrics {
    override fun rateLimited(kind: String) {
        registry.counter(RATE_LIMITED, "kind", kind).increment()
    }

    companion object {
        const val RATE_LIMITED = "paycore.admin.merchant_ops.rate_limited"
    }
}

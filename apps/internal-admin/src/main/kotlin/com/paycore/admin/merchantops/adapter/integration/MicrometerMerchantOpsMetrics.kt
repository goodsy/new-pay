package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import com.paycore.admin.merchantops.application.required.MerchantTargetLabelMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * [MerchantOpsMetrics] Micrometer 구현: `paycore.admin.merchant_ops.rate_limited{kind}`(kind = LOOKUP·REGISTRATION_NUMBER·REGISTRATION — 값·actor 없음),
 * `…command{operation,result}`·`…command_unknown{operation}`(MERCHANT-OPS-002 §7).
 * [MerchantTargetLabelMetrics](MERCHANT-CODE-001 MC-3b): `paycore.admin.ops.merchant_target_labels{result}` — label 값은 고정 분류 6개뿐(그 밖 값은 거부 — 키·상호가 label로 새지 않게).
 */
@Component
class MicrometerMerchantOpsMetrics(private val registry: MeterRegistry) : MerchantOpsMetrics, MerchantTargetLabelMetrics {
    override fun rateLimited(kind: String) {
        registry.counter(RATE_LIMITED, "kind", kind).increment()
    }

    override fun command(operation: String, result: String) {
        registry.counter(COMMAND, "operation", operation, "result", result).increment()
    }

    override fun commandUnknown(operation: String) {
        registry.counter(COMMAND_UNKNOWN, "operation", operation).increment()
    }

    override fun targetLabels(result: String) {
        require(result in TARGET_LABEL_RESULTS) { "target label result class" }
        registry.counter(TARGET_LABELS, "result", result).increment()
    }

    companion object {
        const val RATE_LIMITED = "paycore.admin.merchant_ops.rate_limited"
        const val COMMAND = "paycore.admin.merchant_ops.command"
        const val COMMAND_UNKNOWN = "paycore.admin.merchant_ops.command_unknown"
        const val TARGET_LABELS = "paycore.admin.ops.merchant_target_labels"

        /** 허용 분류. SKIPPED: 이 API는 0개 요청이 400이라 내보내지 않음(TXN-LIST-ENRICH-001 분류 집합과 맞추려고 남겨 둔다). */
        val TARGET_LABEL_RESULTS = setOf("OK", "SKIPPED", "BUSY", "UNAVAILABLE", "UNREADABLE", "RATE_LIMITED")
    }
}

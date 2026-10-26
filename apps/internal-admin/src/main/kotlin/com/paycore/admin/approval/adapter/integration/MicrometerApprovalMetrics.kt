package com.paycore.admin.approval.adapter.integration

import com.paycore.admin.approval.application.required.ApprovalMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 2인 승인 지표(Micrometer, adapter.integration). 경보 기준(운영 설정): [INTEGRITY_VIOLATION] 1건 이상 즉시, [RETRY_LATER] 지속 증가.
 */
@Component
class MicrometerApprovalMetrics(private val registry: MeterRegistry) : ApprovalMetrics {
    override fun decision(operation: String, kind: String, result: String) {
        registry.counter(DECISION, "operation", operation, "kind", kind, "result", result).increment()
    }

    override fun retryLater(operation: String) {
        registry.counter(RETRY_LATER, "operation", operation).increment()
    }

    override fun integrityViolation(check: String) {
        registry.counter(INTEGRITY_VIOLATION, "check", check).increment()
    }

    companion object {
        const val DECISION = "paycore.admin.approval"
        const val RETRY_LATER = "paycore.admin.approval.retry_later"
        const val INTEGRITY_VIOLATION = "paycore.admin.approval.integrity_violation"
    }
}

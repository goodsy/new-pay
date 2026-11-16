package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.required.HomeKpiMetrics
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** [HomeKpiMetrics] Micrometer 구현: counter `paycore.admin.ops.home_kpi{result}`(result는 고정 분류 4개만 — ID·금액·환경 label 없음). */
@Component
class MicrometerHomeKpiMetrics(private val registry: MeterRegistry) : HomeKpiMetrics {
    override fun result(result: String) {
        require(result in RESULTS) { "home kpi result label" }
        registry.counter(NAME, "result", result).increment()
    }

    companion object {
        const val NAME = "paycore.admin.ops.home_kpi"
        val RESULTS = setOf("OK", "FORBIDDEN", "RATE_LIMITED", "UNAVAILABLE")
    }
}

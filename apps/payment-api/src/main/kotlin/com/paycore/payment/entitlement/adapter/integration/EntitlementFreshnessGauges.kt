package com.paycore.payment.entitlement.adapter.integration

import com.paycore.payment.entitlement.application.provided.EntitlementFreshnessQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/** 신선도 gauge(명세 §11): 막힌 head 수·lease 만료 head 수·유효 lease 최소 잔여(초). 조회 시점마다 jOOQ 조회 use case로 계산한다(프로세스 상태 없음). */
@Component
class EntitlementFreshnessGauges(registry: MeterRegistry, private val query: EntitlementFreshnessQuery) {
    init {
        Gauge.builder(MicrometerEntitlementMetrics.BLOCKED_HEADS) { query.overview().blockedHeads.toDouble() }.register(registry)
        Gauge.builder(MicrometerEntitlementMetrics.EXPIRED_HEADS) { query.overview().expiredHeads.toDouble() }.register(registry)
        Gauge.builder(MicrometerEntitlementMetrics.MIN_LEASE_REMAINING) {
            query.overview().minLeaseRemainingMillis?.let { it / MILLIS_PER_SECOND } ?: Double.NaN
        }.baseUnit("seconds").register(registry)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

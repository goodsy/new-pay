package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.provided.EntitlementPropagationQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * 전파 상태 gauge(명세 §11): 미전달 Snapshot 수·최고 대기 연령(초)·격리 수·효력 미확인 최신 revision 수. 값은 조회 시점마다 jOOQ 조회 use case
 * ([EntitlementPropagationQuery], 읽기 전용 TX)로 계산한다 — 프로세스 안에 별도 상태를 두지 않는다.
 */
@Component
class EntitlementPropagationGauges(registry: MeterRegistry, private val query: EntitlementPropagationQuery) {
    private val clock: Clock = Clock.systemUTC()

    init {
        Gauge.builder(MicrometerMasterOperationalMetrics.PENDING) { query.overview().pendingSnapshots.toDouble() }.register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.QUARANTINED) { query.overview().quarantinedSnapshots.toDouble() }.register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.UNCONFIRMED) { query.overview().unconfirmedLatestRevisions.toDouble() }.register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.OLDEST_PENDING_AGE) {
            query.overview().oldestPendingCreatedAt?.let { Duration.between(it, clock.instant()).toMillis() / MILLIS_PER_SECOND } ?: 0.0
        }.baseUnit("seconds").register(registry)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}

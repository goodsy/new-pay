package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.provided.CancellationBacklogQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 취소 적체 gauge(CANCEL-001 §10 Observability): UNKNOWN 취소 수, 가장 오래된 UNKNOWN 연령(초), 대사 이관 뒤 미결 회복 일정 수. 조회 시점마다 provided 조회로 계산한다(프로세스 상태
 * 없음 — 승인 gauge와 같은 방식). label 없음(ID·MID를 넣지 않는다). 취소 사건 hold는 기존 `paycore.payment.incident_holds.active`가 함께 센다.
 */
@Component
class CancellationBacklogGauges(registry: MeterRegistry, private val query: CancellationBacklogQuery) {
    init {
        Gauge.builder(UNKNOWN) { query.backlog().unknownCancellations.toDouble() }.register(registry)
        Gauge.builder(UNKNOWN_OLDEST_AGE) { query.backlog().oldestUnknownAgeSeconds.toDouble() }.register(registry)
        Gauge.builder(ESCALATED) { query.backlog().escalatedOpenSchedules.toDouble() }.register(registry)
    }

    private companion object {
        const val UNKNOWN = "paycore.cancellation.unknown"
        const val UNKNOWN_OLDEST_AGE = "paycore.cancellation.unknown.oldest_age_seconds"
        const val ESCALATED = "paycore.cancellation.escalated"
    }
}

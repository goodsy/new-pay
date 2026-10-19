package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.provided.AuthorizationBacklogQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 승인 적체 gauge(AUTH-001 §9 Observability): OUTCOME_UNKNOWN Attempt 수, 가장 오래된 UNKNOWN 연령(초), 대사 이관 뒤 미결 회복 일정 수, 해제 전 사건 보류 수.
 * 조회 시점마다 provided 조회로 계산한다(프로세스 상태 없음 — [CheckoutBacklogGauges]와 같은 방식). label 없음(ID·MID를 넣지 않는다).
 */
@Component
class AuthorizationBacklogGauges(registry: MeterRegistry, private val query: AuthorizationBacklogQuery) {
    init {
        Gauge.builder(UNKNOWN) { query.backlog().unknownAttempts.toDouble() }.register(registry)
        Gauge.builder(UNKNOWN_OLDEST_AGE) { query.backlog().oldestUnknownAgeSeconds.toDouble() }.register(registry)
        Gauge.builder(ESCALATED) { query.backlog().escalatedOpenSchedules.toDouble() }.register(registry)
        Gauge.builder(ACTIVE_HOLDS) { query.backlog().activeIncidentHolds.toDouble() }.register(registry)
    }

    private companion object {
        const val UNKNOWN = "paycore.authorization.unknown"
        const val UNKNOWN_OLDEST_AGE = "paycore.authorization.unknown.oldest_age_seconds"
        const val ESCALATED = "paycore.authorization.escalated"
        const val ACTIVE_HOLDS = "paycore.payment.incident_holds.active"
    }
}

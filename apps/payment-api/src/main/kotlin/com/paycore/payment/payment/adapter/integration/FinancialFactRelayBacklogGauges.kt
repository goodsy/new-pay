package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.provided.FinancialFactRelayBacklogQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * PAYMENT → FINANCIAL Relay 적체 gauge(FIN-FACT-RELAY-001 §6 Observability — Outbox lag 경보 근거): PENDING 행 수·가장 오래된 PENDING 사실 연령(초)·재시도 중 행 수·
 * 격리 행 수(> 0 경보 — FINANCIAL이 거절한 확정 금융 사실)·아직 발견되지 않은 FINANCIAL_FACT Outbox 행 수. label 없음(ID·MID 없음). 경보 임계값은 운영값(LIVE Gate).
 */
@Component
class FinancialFactRelayBacklogGauges(registry: MeterRegistry, private val query: FinancialFactRelayBacklogQuery) {
    init {
        Gauge.builder(PENDING) { query.backlog().pendingRows.toDouble() }.register(registry)
        Gauge.builder(PENDING_OLDEST_AGE) { query.backlog().oldestPendingAgeSeconds.toDouble() }.register(registry)
        Gauge.builder(RETRYING) { query.backlog().retryingRows.toDouble() }.register(registry)
        Gauge.builder(QUARANTINED) { query.backlog().quarantinedRows.toDouble() }.register(registry)
        Gauge.builder(UNDISCOVERED) { query.backlog().undiscoveredRows.toDouble() }.register(registry)
    }

    companion object {
        const val PENDING = "paycore.financial.relay.pending"
        const val PENDING_OLDEST_AGE = "paycore.financial.relay.pending.oldest_age_seconds"
        const val RETRYING = "paycore.financial.relay.retrying"
        const val QUARANTINED = "paycore.financial.relay.quarantined"
        const val UNDISCOVERED = "paycore.financial.relay.undiscovered"
    }
}

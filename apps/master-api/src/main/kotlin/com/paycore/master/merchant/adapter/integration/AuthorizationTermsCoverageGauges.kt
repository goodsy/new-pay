package com.paycore.master.merchant.adapter.integration

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 승인 시작 조건 누락 gauge(MASTER-SNAPSHOT-002 명세 §9): 통화 활성화가 있는데 한도 정책·수수료·정산 Assignment가 없는 (MID, 통화) 수, ACTIVE 수단 개통이
 * 있는데 ACTIVE 카드사 개통이 없는 수. label은 누락 종류만(`kind`) — MID·통화 식별자 금지. 값은 조회 시점마다 jOOQ 조회로 계산한다(프로세스 상태 없음).
 */
@Component
class AuthorizationTermsCoverageGauges(registry: MeterRegistry, private val query: com.paycore.master.merchant.application.provided.AuthorizationTermsCoverageQuery) {
    init {
        Gauge.builder(MicrometerMasterOperationalMetrics.TERMS_MISSING) { query.overview().enabledCurrenciesWithoutLimitPolicy.toDouble() }
            .tag("kind", "LIMIT_POLICY").register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.TERMS_MISSING) { query.overview().enabledCurrenciesWithoutFeeAssignment.toDouble() }
            .tag("kind", "FEE_ASSIGNMENT").register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.TERMS_MISSING) { query.overview().enabledCurrenciesWithoutSettlementAssignment.toDouble() }
            .tag("kind", "SETTLEMENT_ASSIGNMENT").register(registry)
        Gauge.builder(MicrometerMasterOperationalMetrics.TERMS_MISSING) { query.overview().activeMethodsWithoutCardRoute.toDouble() }
            .tag("kind", "CARD_ROUTE").register(registry)
    }
}

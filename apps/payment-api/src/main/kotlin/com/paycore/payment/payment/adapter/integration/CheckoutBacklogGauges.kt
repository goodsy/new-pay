package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.application.provided.CheckoutBacklogQuery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * 결제창·인증 적체 gauge(AUTHN-001 §15 Observability, §7.5.4): 결제창 시각 지난 REQUESTED 수(주기 작업이 따라오는지), TTL 지난 비종료 인증 세션 수, 만료 지난 미교환 토큰 수.
 * 조회 시점마다 provided 조회로 계산한다(프로세스 상태 없음 — [com.paycore.payment.entitlement.adapter.integration.EntitlementFreshnessGauges]와 같은 방식). label 없음.
 */
@Component
class CheckoutBacklogGauges(registry: MeterRegistry, private val query: CheckoutBacklogQuery) {
    init {
        Gauge.builder(ELAPSED_REQUESTED) { query.backlog().elapsedRequestedPayments.toDouble() }.register(registry)
        Gauge.builder(UNRESOLVED_SESSIONS) { query.backlog().unresolvedAuthenticationSessions.toDouble() }.register(registry)
        Gauge.builder(EXPIRED_UNEXCHANGED_TOKENS) { query.backlog().expiredUnexchangedTokens.toDouble() }.register(registry)
    }

    private companion object {
        const val ELAPSED_REQUESTED = "paycore.payment.checkout_window.elapsed_requested"
        const val UNRESOLVED_SESSIONS = "paycore.authentication.sessions.unresolved"
        const val EXPIRED_UNEXCHANGED_TOKENS = "paycore.checkout.tokens.unexchanged_expired"
    }
}

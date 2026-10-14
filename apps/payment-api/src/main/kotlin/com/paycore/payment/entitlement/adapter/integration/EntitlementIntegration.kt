package com.paycore.payment.entitlement.adapter.integration

import com.paycore.payment.entitlement.application.provided.EntitlementFreshnessQuery
import com.paycore.payment.entitlement.application.required.EntitlementMetrics
import com.paycore.payment.entitlement.application.required.EntitlementTiming
import com.paycore.payment.entitlement.domain.InboxOutcome
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 판정 설정(명세 §8). **운영 기본값이 없다** — 값이 없으면 기동이 실패한다. 시계 오차 여유는 운영 미결(R7)이며 Demo(2초)·시험(100ms) 값은
 * 시험·로컬 설정에만 둔다.
 */
@ConfigurationProperties(prefix = "paycore.payment.entitlement")
class EntitlementSettings(override val clockSkewMargin: Duration) : EntitlementTiming {
    init {
        require(!clockSkewMargin.isNegative && clockSkewMargin < MAX_SKEW) { "clock-skew-margin must be within [0, $MAX_SKEW)" }
    }

    override fun toString(): String = "EntitlementSettings(clockSkewMargin=$clockSkewMargin)"

    private companion object {
        val MAX_SKEW: Duration = Duration.ofMinutes(10)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EntitlementSettings::class)
class EntitlementSettingsConfiguration

/**
 * PAYMENT 권리 지표(Micrometer, 명세 §11). 모니터링 시스템 연동이라 adapter.integration에 둔다. label은 분류값만(outcome·result·reason) —
 * MID·merchantId·eventId 금지(그 값은 구조화 로그에만).
 */
@Component
class MicrometerEntitlementMetrics(private val registry: MeterRegistry) : EntitlementMetrics {
    override fun inboxOutcome(outcome: InboxOutcome) {
        registry.counter(INBOX_OUTCOMES, "outcome", outcome.name).increment()
    }

    override fun inboxRejected(kind: String) {
        registry.counter(INBOX_REJECTED, "kind", kind).increment()
    }

    override fun eligibility(result: String, reason: String?) {
        registry.counter(ELIGIBILITY, "result", result, "reason", reason ?: "NONE").increment()
    }

    companion object {
        const val INBOX_OUTCOMES = "paycore.payment.entitlement.inbox.outcomes"
        const val INBOX_REJECTED = "paycore.payment.entitlement.inbox.rejected"
        const val ELIGIBILITY = "paycore.payment.entitlement.eligibility"
        const val BLOCKED_HEADS = "paycore.payment.entitlement.heads.blocked"
        const val EXPIRED_HEADS = "paycore.payment.entitlement.heads.expired"
        const val MIN_LEASE_REMAINING = "paycore.payment.entitlement.lease.min.remaining"
    }
}

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

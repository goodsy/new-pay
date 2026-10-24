package com.paycore.payment.payment.adapter.integration

import com.paycore.payment.payment.domain.WebhookRetrySchedule
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * delivery 시간 설정(`paycore.webhook.delivery` — WEBHOOK-001 §5.5·§5.6, D-WH-006, WH-17). **운영 기본값 없음**(값이 없거나 범위 밖이면 기동 실패). Demo: claim lease 60s, 재시도 10s~1h·jitter 0.2·10회·24h.
 * 일정 버전은 시도 행에 기록한다(D-AUTH-009 관례). 운영값은 LIVE Gate. 기동 검사는 [MerchantWebhookTimingConsistency].
 */
@ConfigurationProperties(prefix = "paycore.webhook.delivery")
class MerchantWebhookDeliverySettings(val claimLease: Duration, schedule: Schedule) {

    class Schedule(
        val version: String,
        val initial: Duration,
        val max: Duration,
        val jitterRatio: Double,
        val maxAttempts: Int,
        val maxAge: Duration,
    ) {
        fun toDomain(): WebhookRetrySchedule = WebhookRetrySchedule(version, initial, max, jitterRatio, maxAttempts, maxAge)
    }

    val schedule: WebhookRetrySchedule = schedule.toDomain()

    init {
        require(claimLease.isPositive) { "paycore.webhook.delivery.claim-lease must be positive" }
    }

    override fun toString(): String = "MerchantWebhookDeliverySettings(claimLease=$claimLease, schedule=$schedule)"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantWebhookDeliverySettings::class)
class MerchantWebhookDeliverySettingsConfiguration

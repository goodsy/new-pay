package com.paycore.payment.payment.adapter.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** delivery 주기 작업 trigger 설정(`paycore.webhook.delivery.job` — 운영 기본값 없음. Demo: 2초·50건). */
@ConfigurationProperties(prefix = "paycore.webhook.delivery.job")
class MerchantWebhookDeliveryJobSettings(val enabled: Boolean, val fixedDelay: Duration, val batchSize: Int) {
    init {
        require(fixedDelay >= MIN_DELAY) { "webhook delivery fixed-delay must be at least 100ms" }
        require(batchSize in 1..MAX_BATCH) { "webhook delivery batch-size must be within 1..$MAX_BATCH" }
    }

    override fun toString(): String = "MerchantWebhookDeliveryJobSettings(enabled=$enabled, fixedDelay=$fixedDelay, batchSize=$batchSize)"

    private companion object {
        val MIN_DELAY: Duration = Duration.ofMillis(100)
        const val MAX_BATCH = 500
    }
}

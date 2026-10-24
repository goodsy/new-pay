package com.paycore.payment.payment.adapter.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * fan-out 주기 작업 설정(`paycore.webhook.fanout` — **운영 기본값 없음**, 값이 없거나 범위 밖이면 기동 실패. Demo: 5초·100건·실패 재시도 1분).
 */
@ConfigurationProperties(prefix = "paycore.webhook.fanout")
class MerchantNotificationFanoutJobSettings(val enabled: Boolean, val fixedDelay: Duration, val batchSize: Int, val failedRetryInterval: Duration) {
    init {
        require(fixedDelay >= MIN_DELAY) { "webhook fan-out fixed-delay must be at least 100ms" }
        require(batchSize in 1..MAX_BATCH) { "webhook fan-out batch-size must be within 1..$MAX_BATCH" }
        require(failedRetryInterval >= MIN_RETRY) { "webhook fan-out failed-retry-interval must be at least 1s" }
    }

    override fun toString(): String =
        "MerchantNotificationFanoutJobSettings(enabled=$enabled, fixedDelay=$fixedDelay, batchSize=$batchSize, failedRetryInterval=$failedRetryInterval)"

    private companion object {
        val MIN_DELAY: Duration = Duration.ofMillis(100)
        val MIN_RETRY: Duration = Duration.ofSeconds(1)
        const val MAX_BATCH = 1000
    }
}

package com.paycore.payment.payment.adapter.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 결제창 만료 주기 작업 설정(`paycore.payment.checkout-expiry.job`, AUTHN-001 §7.5.4 — **운영 기본값 없음**, 값이 없거나 형식이 틀리면 기동 실패). 시험·로컬은 Demo 값이며
 * 시험에서는 끄고(`enabled: false`) Use Case를 직접 부른다.
 */
@ConfigurationProperties(prefix = "paycore.payment.checkout-expiry.job")
class CheckoutExpiryJobSettings(val enabled: Boolean, val fixedDelay: Duration, val batchSize: Int) {
    init {
        require(fixedDelay >= MIN_DELAY) { "checkout-expiry.job.fixed-delay must be at least 1s" }
        require(batchSize in 1..MAX_BATCH) { "checkout-expiry.job.batch-size must be within 1..$MAX_BATCH" }
    }

    override fun toString(): String = "CheckoutExpiryJobSettings(enabled=$enabled, fixedDelay=$fixedDelay, batchSize=$batchSize)"

    private companion object {
        val MIN_DELAY: Duration = Duration.ofSeconds(1)
        const val MAX_BATCH = 1000
    }
}

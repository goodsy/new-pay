package com.paycore.payment.payment.adapter.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * PAYMENT → FINANCIAL Relay 주기 작업 설정(`paycore.financial-relay.job` — **운영 기본값 없음**, 값이 없거나 범위 밖이면 기동 실패). 전달 기술 설정이며 업무 정책이 아니다.
 * Demo: 1초·50건·claim 30초·backoff 1초~1분·jitter 0.2(운영값 LIVE Gate). claim lease > 전송 상한(연결 + 요청 + 1초)은 integration 기동 검사가 확인한다.
 */
@ConfigurationProperties(prefix = "paycore.financial-relay.job")
class FinancialFactRelayJobSettings(
    val enabled: Boolean,
    val fixedDelay: Duration,
    val batchSize: Int,
    val claimLease: Duration,
    val backoffInitial: Duration,
    val backoffMax: Duration,
    val backoffJitterRatio: Double,
) {
    init {
        require(fixedDelay >= MIN_DELAY) { "financial-relay fixed-delay must be at least 100ms" }
        require(batchSize in 1..MAX_BATCH) { "financial-relay batch-size must be within 1..$MAX_BATCH" }
        require(claimLease.isPositive) { "financial-relay claim-lease must be positive" }
        require(backoffInitial.isPositive && backoffMax >= backoffInitial) { "financial-relay backoff must be positive and max >= initial" }
        require(backoffJitterRatio >= 0.0 && backoffJitterRatio < 1.0) { "financial-relay backoff-jitter-ratio must be within [0, 1)" }
    }

    override fun toString(): String =
        "FinancialFactRelayJobSettings(enabled=$enabled, fixedDelay=$fixedDelay, batchSize=$batchSize, claimLease=$claimLease, backoff=$backoffInitial..$backoffMax)"

    private companion object {
        val MIN_DELAY: Duration = Duration.ofMillis(100)
        const val MAX_BATCH = 500
    }
}

package com.paycore.payment.payment.adapter.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 취소 회복 주기 작업 설정(`paycore.cancellation.timing.recovery`의 trigger 부분, CANCEL-001 CN-29 — **운영 기본값 없음**, 값이 없거나 형식이 틀리면 기동 실패). 조회 간격·예산·연령은 같은
 * 절의 나머지 키로 application이 읽는다(`CancellationTimings`). `adapter.batch`는 required Port·다른 Adapter에 의존하지 않으므로 trigger 키만 따로 바인딩한다.
 */
@ConfigurationProperties(prefix = "paycore.cancellation.timing.recovery")
class CancellationRecoveryJobSettings(val enabled: Boolean, val fixedDelay: Duration, val batchSize: Int) {
    init {
        require(fixedDelay >= MIN_DELAY) { "cancellation recovery fixed-delay must be at least 1s" }
        require(batchSize in 1..MAX_BATCH) { "cancellation recovery batch-size must be within 1..$MAX_BATCH" }
    }

    override fun toString(): String = "CancellationRecoveryJobSettings(enabled=$enabled, fixedDelay=$fixedDelay, batchSize=$batchSize)"

    private companion object {
        val MIN_DELAY: Duration = Duration.ofSeconds(1)
        const val MAX_BATCH = 1000
    }
}

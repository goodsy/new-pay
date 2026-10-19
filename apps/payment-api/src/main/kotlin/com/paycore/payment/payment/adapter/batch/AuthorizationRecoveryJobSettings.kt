package com.paycore.payment.payment.adapter.batch

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 승인 회복 주기 작업 설정(`paycore.authorization.timing.recovery`의 trigger 부분, AUTH-001 §6.1 — **운영 기본값 없음**, 값이 없거나 형식이 틀리면 기동 실패). 조회 간격·예산·연령은 같은 절의
 * 나머지 키로 application이 읽는다(`AuthorizationTimings`). `adapter.batch`는 required Port·다른 Adapter에 의존하지 않으므로 trigger 키만 따로 바인딩한다(ArchUnit
 * `batchAdapterUsesOnlyApplication`). 시험·로컬은 Demo 값이며 시험에서는 끄고(`enabled: false`) Use Case를 직접 부른다.
 */
@ConfigurationProperties(prefix = "paycore.authorization.timing.recovery")
class AuthorizationRecoveryJobSettings(val enabled: Boolean, val fixedDelay: Duration, val batchSize: Int) {
    init {
        require(fixedDelay >= MIN_DELAY) { "authorization recovery fixed-delay must be at least 1s" }
        require(batchSize in 1..MAX_BATCH) { "authorization recovery batch-size must be within 1..$MAX_BATCH" }
    }

    override fun toString(): String = "AuthorizationRecoveryJobSettings(enabled=$enabled, fixedDelay=$fixedDelay, batchSize=$batchSize)"

    private companion object {
        val MIN_DELAY: Duration = Duration.ofSeconds(1)
        const val MAX_BATCH = 1000
    }
}

package com.paycore.payment.payment.domain

import java.time.Duration
import java.time.Instant

/**
 * 가맹점 Webhook 재시도 일정(WEBHOOK-001 §5.6, D-WH-006). 수치는 기본값 없는 설정에서만 온다(Demo 10s~1h·10회·24h — 운영값 아님, LIVE Gate). 지수 backoff
 * `initial × 2^(n−1)`(상한 [max]) + jitter(`base × jitterRatio × fraction`, 상한 [max]). 포기는 시도 수 또는 첫 시도 후 경과 중 먼저 닿는 것.
 */
data class WebhookRetrySchedule(
    val version: String,
    val initial: Duration,
    val max: Duration,
    val jitterRatio: Double,
    val maxAttempts: Int,
    val maxAge: Duration,
) {
    init {
        require(version.length in 1..MAX_VERSION) { "retry schedule version must be 1..$MAX_VERSION characters" }
        require(initial.isPositive) { "initial backoff must be positive" }
        require(max >= initial) { "max backoff must not be below the initial backoff" }
        require(jitterRatio >= 0.0 && jitterRatio < 1.0) { "jitter ratio must be within [0, 1)" }
        require(maxAttempts >= 1) { "max attempts must be positive" }
        require(maxAge.isPositive) { "max age must be positive" }
    }

    /** [attemptsMade]번째 시도(1부터)가 실패한 뒤 다음 시도까지의 대기. [jitterFraction]은 [0, 1) 난수. */
    fun delayAfter(attemptsMade: Int, jitterFraction: Double): Duration {
        require(attemptsMade >= 1) { "attempts made must be positive" }
        require(jitterFraction >= 0.0 && jitterFraction < 1.0) { "jitter fraction must be within [0, 1)" }
        val maxMillis = max.toMillis()
        var base = initial.toMillis()
        var doublings = attemptsMade - 1
        while (doublings > 0 && base < maxMillis) {
            base = if (base > maxMillis / 2) maxMillis else base * 2
            doublings--
        }
        base = base.coerceAtMost(maxMillis)
        val jitter = (base * jitterRatio * jitterFraction).toLong()
        return Duration.ofMillis((base + jitter).coerceAtMost(maxMillis))
    }

    /** [attemptsMade]번 시도한 뒤 포기할지(경계 포함: 시도 수 = 상한, 경과 = 상한). */
    fun exhausted(attemptsMade: Int, firstAttemptAt: Instant, now: Instant): Boolean =
        attemptsMade >= maxAttempts || !now.isBefore(firstAttemptAt.plus(maxAge))

    private companion object {
        const val MAX_VERSION = 64
    }
}

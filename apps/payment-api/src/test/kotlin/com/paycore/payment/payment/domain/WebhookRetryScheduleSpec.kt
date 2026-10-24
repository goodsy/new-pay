package com.paycore.payment.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * 재시도 일정(WEBHOOK-001 §5.6, D-WH-006). Demo 값(10s 시작·1h 상한·10회·24h) — 운영값 아님. 기대 수열은 명세 식(initial × 2^(n−1), 상한 max, jitter는 비율 × 난수)에서 직접 계산해 적는다.
 */
class WebhookRetryScheduleSpec : BehaviorSpec({

    val demo = WebhookRetrySchedule(
        version = "webhook-retry-demo-1", initial = Duration.ofSeconds(10), max = Duration.ofHours(1), jitterRatio = 0.2, maxAttempts = 10, maxAge = Duration.ofHours(24),
    )
    val t0 = Instant.parse("2026-10-21T00:00:00Z")

    Given("the backoff without jitter (fraction 0)") {
        Then("10s, 20s, 40s, 80s, 160s, 320s, 640s, 1280s, 2560s, then capped at 1h") {
            (1..12).map { demo.delayAfter(it, 0.0).seconds } shouldBe listOf(10L, 20L, 40L, 80L, 160L, 320L, 640L, 1280L, 2560L, 3600L, 3600L, 3600L)
        }
        Then("a huge attempt count does not overflow and stays at the cap") {
            demo.delayAfter(Int.MAX_VALUE, 0.0) shouldBe Duration.ofHours(1)
        }
    }

    Given("jitter") {
        Then("it adds ratio × fraction of the base, never above the cap") {
            demo.delayAfter(1, 0.5) shouldBe Duration.ofSeconds(11) // 10 + 10 × 0.2 × 0.5
            demo.delayAfter(2, 0.999).toMillis() shouldBe 23_996L // 20,000 + floor(20,000 × 0.2 × 0.999)
            demo.delayAfter(10, 0.999) shouldBe Duration.ofHours(1)
        }
        Then("a fraction outside [0, 1) or an attempt count below 1 is a defect") {
            shouldThrow<IllegalArgumentException> { demo.delayAfter(1, 1.0) }
            shouldThrow<IllegalArgumentException> { demo.delayAfter(1, -0.1) }
            shouldThrow<IllegalArgumentException> { demo.delayAfter(0, 0.0) }
        }
    }

    Given("give-up (either cap — D-WH-006)") {
        Then("attempt count reaching maxAttempts gives up") {
            demo.exhausted(9, t0, t0.plusSeconds(60)) shouldBe false
            demo.exhausted(10, t0, t0.plusSeconds(60)) shouldBe true
        }
        Then("elapsed time since the first attempt reaching maxAge gives up (boundary inclusive)") {
            demo.exhausted(2, t0, t0.plus(Duration.ofHours(24)).minusMillis(1)) shouldBe false
            demo.exhausted(2, t0, t0.plus(Duration.ofHours(24))) shouldBe true
        }
    }

    Given("settings validation (no operational defaults — values must be explicit and sane)") {
        Then("invalid values are rejected") {
            shouldThrow<IllegalArgumentException> { demo.copy(version = "") }
            shouldThrow<IllegalArgumentException> { demo.copy(version = "x".repeat(65)) }
            shouldThrow<IllegalArgumentException> { demo.copy(initial = Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { demo.copy(max = Duration.ofSeconds(9)) }
            shouldThrow<IllegalArgumentException> { demo.copy(jitterRatio = 1.0) }
            shouldThrow<IllegalArgumentException> { demo.copy(jitterRatio = -0.01) }
            shouldThrow<IllegalArgumentException> { demo.copy(maxAttempts = 0) }
            shouldThrow<IllegalArgumentException> { demo.copy(maxAge = Duration.ZERO) }
        }
    }
})

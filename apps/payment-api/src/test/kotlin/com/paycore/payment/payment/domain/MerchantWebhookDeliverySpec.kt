package com.paycore.payment.payment.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * 전달 Aggregate(WEBHOOK-001 §5.0 rev 0.2 — m-2 시도 번호 예약, n-1 성공 단조, §5.6·§5.8, D-WH-006): PENDING → 시도 예약 → DELIVERED(2xx) / 실패마다 다음 시각 / 상한 → GAVE_UP(완료 아님).
 * 존재할 수 없는 조합은 reconstitute가 거부한다(영속 행 검증).
 */
class MerchantWebhookDeliverySpec : BehaviorSpec({

    val t0 = Instant.parse("2026-10-21T00:00:00Z")
    val schedule = WebhookRetrySchedule("webhook-retry-demo-1", Duration.ofSeconds(10), Duration.ofHours(1), 0.0, 3, Duration.ofHours(24))
    val deliveryId = MerchantWebhookDeliveryId("whd_01JB0000000000000000000000")
    val eventId = MerchantWebhookEventId("whe_01JB0000000000000000000000")

    fun pending() = MerchantWebhookDelivery.pending(deliveryId, eventId, Environment.TEST, MerchantAccountId("mid_demo_1"), t0)

    fun fail(d: MerchantWebhookDelivery, at: Instant, code: String = "HTTP_500") {
        d.startAttempt(at)
        d.recordFailure(at, code, schedule, 0.0)
    }

    Given("a new delivery") {
        Then("it is PENDING, due immediately, with no attempt") {
            val d = pending()
            d.status shouldBe MerchantWebhookDeliveryStatus.PENDING
            d.nextAttemptAt shouldBe t0
            d.attemptCount shouldBe 0
            d.firstAttemptAt shouldBe null
            d.lastFailureCode shouldBe null
        }
        Then("a result cannot be recorded without a reserved attempt") {
            shouldThrow<IllegalStateException> { pending().recordSuccess(t0) }
            shouldThrow<IllegalStateException> { pending().recordFailure(t0, "HTTP_500", schedule, 0.0) }
        }
    }

    Given("attempt numbers reserved at claim time (m-2)") {
        Then("each claim reserves the next number from 1 and fixes the first attempt time once") {
            val d = pending()
            d.startAttempt(t0) shouldBe 1
            d.startAttempt(t0.plusSeconds(60)) shouldBe 2 // lease 인수: 앞 worker 결과 없이 다음 번호 — 두 시도 행 모두 남는다
            d.firstAttemptAt shouldBe t0
            d.attemptCount shouldBe 2
        }
        Then("a finished delivery reserves no attempt") {
            val d = pending().also { it.startAttempt(t0); it.recordSuccess(t0) }
            shouldThrow<IllegalStateException> { d.startAttempt(t0) }
        }
    }

    Given("H-1: a reservation released before sending (the lease could not cover one attempt)") {
        Then("the reserved number is returned (attempt count = real sends), the first-attempt time is cleared if nothing was sent before") {
            val d = pending()
            d.startAttempt(t0) shouldBe 1
            d.releaseReservedAttempt(1)
            d.attemptCount shouldBe 0
            d.firstAttemptAt shouldBe null
            d.status shouldBe MerchantWebhookDeliveryStatus.PENDING
            d.startAttempt(t0.plusSeconds(1)) shouldBe 1
            d.firstAttemptAt shouldBe t0.plusSeconds(1)
        }
        Then("after an earlier real attempt the first-attempt time stays")  {
            val d = pending()
            fail(d, t0)
            d.startAttempt(t0.plusSeconds(10)) shouldBe 2
            d.releaseReservedAttempt(2)
            d.attemptCount shouldBe 1
            d.firstAttemptAt shouldBe t0
        }
        Then("only the latest reservation of a pending delivery can be released") {
            val d = pending().also { it.startAttempt(t0); it.startAttempt(t0) }
            shouldThrow<IllegalStateException> { d.releaseReservedAttempt(1) }
            val done = pending().also { it.startAttempt(t0); it.recordSuccess(t0) }
            shouldThrow<IllegalStateException> { done.releaseReservedAttempt(1) }
        }
    }

    Given("a successful attempt") {
        Then("it becomes DELIVERED with no next attempt") {
            val d = pending()
            d.startAttempt(t0.plusSeconds(1))
            d.recordSuccess(t0.plusSeconds(1))
            d.status shouldBe MerchantWebhookDeliveryStatus.DELIVERED
            d.attemptCount shouldBe 1
            d.firstAttemptAt shouldBe t0.plusSeconds(1)
            d.deliveredAt shouldBe t0.plusSeconds(1)
            d.nextAttemptAt shouldBe null
        }
        Then("success is monotonic: a repeated 2xx keeps the first delivery time, and a late 2xx after GAVE_UP still delivers (n-1)") {
            val d = pending().also { it.startAttempt(t0); it.recordSuccess(t0) }
            d.recordSuccess(t0.plusSeconds(5))
            d.deliveredAt shouldBe t0
            val gaveUp = pending().also { x -> repeat(3) { fail(x, t0) } }
            gaveUp.status shouldBe MerchantWebhookDeliveryStatus.GAVE_UP
            gaveUp.recordSuccess(t0.plusSeconds(9))
            gaveUp.status shouldBe MerchantWebhookDeliveryStatus.DELIVERED
            gaveUp.deliveredAt shouldBe t0.plusSeconds(9)
            gaveUp.gaveUpAt shouldBe null
            MerchantWebhookDelivery.reconstitute(gaveUp.state()).status shouldBe MerchantWebhookDeliveryStatus.DELIVERED
        }
    }

    Given("failed attempts") {
        Then("each failure schedules the next attempt by the backoff, and the cap gives up (GAVE_UP is not delivered)") {
            val d = pending()
            fail(d, t0)
            d.status shouldBe MerchantWebhookDeliveryStatus.PENDING
            d.nextAttemptAt shouldBe t0.plusSeconds(10)
            fail(d, t0.plusSeconds(10), "TIMEOUT")
            d.nextAttemptAt shouldBe t0.plusSeconds(30)
            d.attemptCount shouldBe 2
            d.firstAttemptAt shouldBe t0
            fail(d, t0.plusSeconds(30), "HTTP_404")
            d.status shouldBe MerchantWebhookDeliveryStatus.GAVE_UP
            d.gaveUpAt shouldBe t0.plusSeconds(30)
            d.nextAttemptAt shouldBe null
            d.lastFailureCode shouldBe "HTTP_404"
            d.deliveredAt shouldBe null
        }
        Then("a success after failures delivers") {
            val d = pending()
            fail(d, t0, "CONNECTION")
            d.startAttempt(t0.plusSeconds(10))
            d.recordSuccess(t0.plusSeconds(10))
            d.status shouldBe MerchantWebhookDeliveryStatus.DELIVERED
            d.attemptCount shouldBe 2
            d.lastFailureCode shouldBe "CONNECTION"
        }
        Then("a failure never undoes a delivery or a give-up") {
            val delivered = pending().also { it.startAttempt(t0); it.recordSuccess(t0) }
            shouldThrow<IllegalStateException> { delivered.recordFailure(t0, "HTTP_500", schedule, 0.0) }
            val gaveUp = pending().also { x -> repeat(3) { fail(x, t0) } }
            shouldThrow<IllegalStateException> { gaveUp.recordFailure(t0, "HTTP_500", schedule, 0.0) }
        }
    }

    Given("failure codes") {
        Then("only short fixed codes are accepted (no free text, no URL or body)") {
            listOf("connection refused to https://x", "", "A".repeat(49), "http_500").forEach { code ->
                val d = pending().also { it.startAttempt(t0) }
                shouldThrow<IllegalArgumentException> { d.recordFailure(t0, code, schedule, 0.0) }
            }
        }
    }

    Given("reconstitution of stored rows") {
        val base = MerchantWebhookDeliveryState(
            deliveryId, eventId, Environment.TEST, MerchantAccountId("mid_demo_1"), MerchantWebhookDeliveryStatus.PENDING, 0, t0, null, null, null, null,
        )
        Then("consistent rows restore (including a pending row with a reserved attempt)") {
            MerchantWebhookDelivery.reconstitute(base).status shouldBe MerchantWebhookDeliveryStatus.PENDING
            MerchantWebhookDelivery.reconstitute(base.copy(attemptCount = 1, firstAttemptAt = t0)).attemptCount shouldBe 1
            MerchantWebhookDelivery.reconstitute(
                base.copy(status = MerchantWebhookDeliveryStatus.DELIVERED, attemptCount = 1, nextAttemptAt = null, firstAttemptAt = t0, deliveredAt = t0),
            ).status shouldBe MerchantWebhookDeliveryStatus.DELIVERED
            MerchantWebhookDelivery.reconstitute(
                base.copy(status = MerchantWebhookDeliveryStatus.GAVE_UP, attemptCount = 3, nextAttemptAt = null, firstAttemptAt = t0, lastFailureCode = "HTTP_500", gaveUpAt = t0),
            ).status shouldBe MerchantWebhookDeliveryStatus.GAVE_UP
        }
        Then("impossible combinations are rejected") {
            listOf(
                base.copy(nextAttemptAt = null),
                base.copy(attemptCount = 1),
                base.copy(firstAttemptAt = t0),
                base.copy(deliveredAt = t0),
                base.copy(status = MerchantWebhookDeliveryStatus.DELIVERED, nextAttemptAt = null),
                base.copy(status = MerchantWebhookDeliveryStatus.DELIVERED, attemptCount = 1, firstAttemptAt = t0, deliveredAt = t0),
                base.copy(status = MerchantWebhookDeliveryStatus.GAVE_UP, attemptCount = 1, nextAttemptAt = null, firstAttemptAt = t0, gaveUpAt = t0),
                base.copy(status = MerchantWebhookDeliveryStatus.GAVE_UP, attemptCount = 1, nextAttemptAt = null, firstAttemptAt = t0, lastFailureCode = "X", gaveUpAt = t0, deliveredAt = t0),
                base.copy(attemptCount = -1),
                base.copy(attemptCount = 1, firstAttemptAt = t0, lastFailureCode = "not a code"),
            ).forEach { shouldThrow<IllegalArgumentException> { MerchantWebhookDelivery.reconstitute(it) } }
        }
    }

    Given("identifiers") {
        Then("they have the server prefix and a 26-character ULID") {
            shouldThrow<IllegalArgumentException> { MerchantWebhookEventId("whd_01JB0000000000000000000000") }
            shouldThrow<IllegalArgumentException> { MerchantWebhookEventId("whe_short") }
            shouldThrow<IllegalArgumentException> { MerchantWebhookDeliveryId("whe_01JB0000000000000000000000") }
            shouldThrow<IllegalArgumentException> { MerchantWebhookDeliveryId("whd_01JB000000000000000000000I") }
        }
    }
})

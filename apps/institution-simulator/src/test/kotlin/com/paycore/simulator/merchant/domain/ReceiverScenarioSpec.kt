package com.paycore.simulator.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * 가상 가맹점 수신기 응답 시나리오(WEBHOOK-001 §9 — Demo, 운영 동작 아님). 같은 이벤트의 앞선 **검증된** 전달 수로 응답을 정한다(n회 5xx 후 2xx). 2xx만 "받음"이다(WH-09).
 */
class ReceiverScenarioSpec : BehaviorSpec({

    Given("each scenario") {
        Then("OK answers 200 immediately") {
            ReceiverScenario.Ok.reply(0) shouldBe ReceiverReply(200, Duration.ZERO, 0)
            ReceiverScenario.Ok.reply(0).accepted shouldBe true
        }
        Then("FAIL_THEN_OK answers 503 for the first n verified deliveries of the event, then 200") {
            val s = ReceiverScenario.FailThenOk(2)
            (0..3).map { s.reply(it).status } shouldBe listOf(503, 503, 200, 200)
            s.reply(1).accepted shouldBe false
        }
        Then("DELAY answers 200 after the delay; REDIRECT answers 302 (not accepted); LARGE_BODY answers 200 with the configured body size") {
            ReceiverScenario.Delayed(Duration.ofMillis(1500)).reply(0) shouldBe ReceiverReply(200, Duration.ofMillis(1500), 0)
            ReceiverScenario.Redirect.reply(0).let { it.status shouldBe 302; it.accepted shouldBe false }
            ReceiverScenario.LargeBody(65_536).reply(0) shouldBe ReceiverReply(200, Duration.ZERO, 65_536)
        }
        Then("invalid parameters are refused (negative failures, non-positive delay, body outside 1..1 MiB)") {
            shouldThrow<IllegalArgumentException> { ReceiverScenario.FailThenOk(-1) }
            shouldThrow<IllegalArgumentException> { ReceiverScenario.Delayed(Duration.ZERO) }
            shouldThrow<IllegalArgumentException> { ReceiverScenario.LargeBody(0) }
            shouldThrow<IllegalArgumentException> { ReceiverScenario.LargeBody(1_048_577) }
        }
    }
})

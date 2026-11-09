package com.paycore.master.merchant.domain

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * MERCHANT-OPS-002 §3.4·§8(D-MOPS-006 ⑤): [SeoulMidnight.startOfDay] = 판정 시각이 속한 **Asia/Seoul 날짜의 00:00**(UTC 시각). KST = UTC+9라 경계는 UTC 15:00이다.
 */
class SeoulMidnightSpec : BehaviorSpec({

    Given("startOfDay around the UTC 15:00 boundary (KST 00:00)") {
        Then("each instant maps to the KST midnight of its own KST date, and the result is itself a Seoul midnight boundary") {
            val table = linkedMapOf(
                "2026-11-08T14:59:59.999Z" to "2026-11-07T15:00:00Z", // KST 11-08 23:59:59.999
                "2026-11-08T15:00:00Z" to "2026-11-08T15:00:00Z", // KST 11-09 00:00
                "2026-11-08T15:00:00.001Z" to "2026-11-08T15:00:00Z",
                "2026-11-09T14:59:59.999Z" to "2026-11-08T15:00:00Z",
                "2026-11-08T00:00:00Z" to "2026-11-07T15:00:00Z", // KST 11-08 09:00
                "2026-12-31T15:00:00Z" to "2026-12-31T15:00:00Z", // KST 2027-01-01 00:00
            )
            table.forEach { (at, midnight) ->
                withClue(at) {
                    SeoulMidnight.startOfDay(Instant.parse(at)) shouldBe Instant.parse(midnight)
                    SeoulMidnight.isBoundary(SeoulMidnight.startOfDay(Instant.parse(at))) shouldBe true
                }
            }
        }
    }
})

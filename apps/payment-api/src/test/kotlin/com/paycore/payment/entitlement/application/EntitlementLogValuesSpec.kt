package com.paycore.payment.entitlement.application

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.MDC

/** entitlement 로그 값 도구(CP-API-001 review round2 m-2·n-e). */
class EntitlementLogValuesSpec : BehaviorSpec({
    Given("an externally supplied identifier written to a log line") {
        Then("control characters, line/paragraph separators and bidi controls are escaped so the value stays on one line; normal ids are unchanged") {
            EntitlementLogValues.safe("site_01HZY3K8Q4M2N6P8R0T2V4X6Z9") shouldBe "site_01HZY3K8Q4M2N6P8R0T2V4X6Z9"
            EntitlementLogValues.safe(null) shouldBe null
            val forged = EntitlementLogValues.safe("x\nINFO forged\r ‮\u0000")!!
            forged shouldBe "x\\u000AINFO forged\\u000D\\u2028\\u202E\\u0000"
            listOf("\n", "\r", " ", "‮", "\u0000").forEach { forged shouldNotContain it }
            // 백슬래시를 먼저 이스케이프하므로 원문 문자열 `\u000A`(6글자)와 실제 개행이 같은 로그 표기가 되지 않는다.
            EntitlementLogValues.safe("\\u000A") shouldBe "\\\\u000A"
        }
        Then("long values are clipped") {
            EntitlementLogValues.safe("a".repeat(200)) shouldBe "a".repeat(128) + "…"
        }
    }
    Given("the request correlation id") {
        Then("it is read from the MDC key the web entry filter sets, or null outside a request") {
            EntitlementLogValues.correlationId() shouldBe null
            MDC.put("correlationId", "req-1")
            try {
                EntitlementLogValues.correlationId() shouldBe "req-1"
            } finally {
                MDC.remove("correlationId")
            }
        }
    }
})

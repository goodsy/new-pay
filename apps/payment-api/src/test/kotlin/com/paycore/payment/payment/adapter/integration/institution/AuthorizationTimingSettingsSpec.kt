package com.paycore.payment.payment.adapter.integration.institution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration

/**
 * 승인 시간 설정(AUTH-001 §4.4·§4.5·§6, Demo)과 기동 검사 AU-34: 모든 Route에서 `connectTimeout × (connectRetryBudget + 1) < dispatchLease − safetyMargin` — 전송자가 lease 만료 뒤
 * 늦게 첫 송신을 하지 않게 한다(rev 0.3 m-2). 위반이면 기동 실패.
 */
class AuthorizationTimingSettingsSpec : BehaviorSpec({

    fun timings(
        lease: Duration = Duration.ofSeconds(30),
        margin: Duration = Duration.ofSeconds(5),
        maxAge: Duration = Duration.ofHours(1),
        version: String = "recovery-demo-1",
    ) = AuthorizationTimingSettings(
        dispatch = AuthorizationTimingSettings.Dispatch(lease = lease, safetyMargin = margin, preparedDeadline = Duration.ofSeconds(3), gateRetryInterval = Duration.ofMillis(200)),
        recovery = AuthorizationTimingSettings.Recovery(
            enabled = false, fixedDelay = Duration.ofSeconds(5), batchSize = 50, inquiryInterval = Duration.ofSeconds(10), inquiryBudget = 5, maxAge = maxAge,
            slowInquiryInterval = Duration.ofMinutes(10), version = version,
        ),
    )

    Given("route connect budgets and the dispatch lease") {
        Then("connectTimeout × (budget + 1) below lease − margin passes") {
            AuthorizationTimingConsistency(timings(), VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9))).verify()
        }
        Then("a connect budget reaching lease − margin refuses the startup (AU-34)") {
            val slow = VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, connectTimeout = Duration.ofSeconds(9), connectRetryBudget = 2, readTimeout = Duration.ofSeconds(1)))
            shouldThrow<IllegalStateException> { AuthorizationTimingConsistency(timings(), slow).verify() }.message!! shouldContain "AU-34"
            shouldThrow<IllegalArgumentException> { timings(lease = Duration.ofSeconds(5), margin = Duration.ofSeconds(5)) }
        }
    }

    // 검토 #3 m-2: 조회 보존기간이 지나면 기관은 "없음"을 확인해 줄 수 없다 — 그 전에 이관하고(maxAge), 종결 요청은 보존기간 안에서만, 그리고 전송이 끝났을 수 있는 시간 뒤에만 보낸다.
    Given("recovery timings against every route's capabilities (review #3 m-2)") {
        Then("maxAge < min(inquiryRetention), closableAfter < inquiryRetention and closableAfter ≥ preparedDeadline + dispatchLease pass") {
            AuthorizationTimingConsistency(timings(), VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9), "SIMVANB" to VanRouteFixture.route(9, confirmsNoEffect = false))).verify()
        }
        Then("maxAge reaching a route's inquiry retention refuses the startup") {
            val short = VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9), "SIMVANB" to VanRouteFixture.route(9, inquiryRetention = Duration.ofHours(1)))
            shouldThrow<IllegalStateException> { AuthorizationTimingConsistency(timings(maxAge = Duration.ofHours(1)), short).verify() }.message!! shouldContain "maxAge"
        }
        Then("closableAfter reaching the inquiry retention refuses the startup") {
            val r = VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, closableAfter = Duration.ofDays(7), inquiryRetention = Duration.ofDays(7)))
            shouldThrow<IllegalStateException> { AuthorizationTimingConsistency(timings(), r).verify() }.message!! shouldContain "closableAfter"
        }
        Then("closableAfter shorter than preparedDeadline + dispatchLease refuses the startup (a closure could race a send still allowed)") {
            val r = VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, closableAfter = Duration.ofSeconds(32)))
            shouldThrow<IllegalStateException> { AuthorizationTimingConsistency(timings(), r).verify() }.message!! shouldContain "closableAfter"
            AuthorizationTimingConsistency(timings(), VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, closableAfter = Duration.ofSeconds(33)))).verify()
        }
    }

    // 검토 #3 m-4: 회복 설정 버전은 필수(운영 기본값 없음) — 일정 행과 이관 이벤트에 남는다.
    Given("the recovery config version (review #3 m-4)") {
        Then("a blank version refuses the startup") {
            shouldThrow<IllegalArgumentException> { timings(version = " ") }
            shouldThrow<IllegalArgumentException> { timings(version = "v".repeat(33)) }
            timings().recoveryConfigVersion shouldBe "recovery-demo-1"
        }
    }
})

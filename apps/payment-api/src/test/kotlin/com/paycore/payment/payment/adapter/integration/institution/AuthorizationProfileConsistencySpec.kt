package com.paycore.payment.payment.adapter.integration.institution

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import java.time.Duration

/**
 * 결제창 Plan Demo 정책 ↔ VAN Route Capability 일치 기동 검사(AUTH-001 AU-31, rev 0.3 n-7, D-AUTH1-003 "결제창 Demo 설정 일치"): 통화별 허용 할부 개월과 할부 최소금액이
 * 다르면 기동 실패 — 인증을 마친 고객이 승인 단계에서 처음 거절되는 경로를 막는다. 체크카드 할부 거절은 설정이 아니라 고정 domain 규칙이라 비교하지 않는다.
 */
class AuthorizationProfileConsistencySpec : BehaviorSpec({

    fun authentication(months: List<Int> = listOf(2, 3, 6), minimum: Long? = 50_000, currency: String = "KRW") = AuthenticationSettings(
        backends = mapOf("TEST" to AuthenticationSettings.Backend("SIMULATOR", "sim-auth-demo")),
        profiles = mapOf(
            "sim-auth-demo" to AuthenticationSettings.Profile(
                "sim-auth-demo-1", listOf(AuthenticationSettings.CardCompany("SIMISSA", "가상카드A")),
                AuthenticationSettings.PlanPolicy("plan-demo-1", currency, months, minimum),
            ),
        ),
        simulator = AuthenticationSettings.SimulatorConnection("https://sim.example.test", "https://sim.example.test", "test-only-key", Duration.ofSeconds(1), Duration.ofSeconds(1)),
    )

    fun routes(months: List<Int> = listOf(2, 3, 6), minimum: Long? = 50_000) = ConfiguredInstitutionRouteRegistry(
        backends = mapOf("TEST" to ConfiguredInstitutionRouteRegistry.Backend("SIMULATOR")),
        routes = mapOf(
            "SIMVAN01" to VanRouteFixture.route(9).let { r ->
                ConfiguredInstitutionRouteRegistry.Route(
                    r.environment, r.institution, r.protocolVersion, r.profileVersion, r.host, r.port, r.keyId,
                    java.util.Base64.getEncoder().encodeToString(StubVan.KEY), r.connectTimeout, r.readTimeout, r.totalDeadline, r.connectRetryBudget,
                    VanRouteFixture.capabilities().let { c ->
                        ConfiguredInstitutionRouteRegistry.Capabilities(
                            c.confirmsNoEffect, c.closableAfter, c.inquiryRetention, c.sameIdResendSafe, c.authenticationEvidenceReusableForResend,
                            c.supportedCurrencies, c.supportedCardTypes, c.installmentCardTypes, months, minimum,
                        )
                    },
                )
            },
        ),
    )

    Given("the checkout plan policy and the VAN route capability") {
        Then("equal months and minimum amount for the currency pass") {
            AuthorizationProfileConsistency(authentication(), routes()).verify()
        }
        Then("different months or a different minimum installment amount refuse the startup") {
            mapOf(
                "months" to AuthorizationProfileConsistency(authentication(months = listOf(2, 3, 6, 12)), routes()),
                "route months" to AuthorizationProfileConsistency(authentication(), routes(months = listOf(2, 3))),
                "minimum" to AuthorizationProfileConsistency(authentication(minimum = 30_000), routes()),
                "route minimum missing" to AuthorizationProfileConsistency(authentication(), routes(minimum = null)),
            ).forEach { (name, check) ->
                withClue(name) { shouldThrow<IllegalStateException> { check.verify() }.message!! shouldContain "AU-31" }
            }
        }
        Then("a plan policy for a currency the route does not support is not compared (the confirm refuses that currency first — D-AUTH1-002)") {
            AuthorizationProfileConsistency(authentication(currency = "USD", months = listOf(3)), routes()).verify()
        }
    }
})

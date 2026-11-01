package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.adapter.integration.institution.AuthenticationSettingsFixture.connection
import com.paycore.payment.payment.adapter.integration.institution.AuthenticationSettingsFixture.settings
import com.paycore.payment.payment.domain.Environment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Simulator/REAL 격리(AUTHN-001 §13.6, D-AUTHN-004, Coordinator 승인 — "TEST 외 환경" = Payment environment 축): 기동 설정 검사가 LIVE의 Simulator, REAL·모르는
 * backend, 없는 profile, 잘못된 Simulator URL을 거부한다(설정 바인딩 실패 = 기동 실패). LIVE에는 backend가 없어 결제창이 fail-closed다.
 */
class AuthenticationBackendIsolationSpec : FunSpec({

    test("TEST with a SIMULATOR backend resolves its profile; LIVE has no backend (checkout not supported, fail-closed)") {
        val s = settings()
        s.profileFor(Environment.TEST)!!.let { listOf(it.id, it.version, it.allows("SIMISSA"), it.allows("SIMISSZ")) } shouldBe
            listOf("sim-auth-demo", "sim-auth-demo-1", true, false)
        s.profileFor(Environment.LIVE) shouldBe null
    }

    test("start-up is refused for LIVE: SIMULATOR, REAL or unknown backend types, an unknown environment or a missing profile") {
        mapOf(
            "LIVE simulator" to mapOf("LIVE" to AuthenticationSettings.Backend("SIMULATOR", "sim-auth-demo")),
            "REAL on TEST" to mapOf("TEST" to AuthenticationSettings.Backend("REAL", "sim-auth-demo")),
            "REAL on LIVE" to mapOf("LIVE" to AuthenticationSettings.Backend("REAL", "sim-auth-demo")),
            "unknown type" to mapOf("TEST" to AuthenticationSettings.Backend("VAN", "sim-auth-demo")),
            "unknown environment" to mapOf("STAGING" to AuthenticationSettings.Backend("SIMULATOR", "sim-auth-demo")),
            "missing profile" to mapOf("TEST" to AuthenticationSettings.Backend("SIMULATOR", "none")),
        ).forEach { (name, backends) -> withClue(name) { shouldThrow<IllegalArgumentException> { settings(backends = backends) } } }
    }

    test("Simulator URLs: no userinfo, path or query; customer origin https only; server URL https or local loopback http") {
        listOf(
            "http://evil.example.test", "https://user@sim.example.test", "https://sim.example.test/path", "https://sim.example.test?x=1", "ftp://sim.example.test",
        ).forEach { url -> withClue(url) { shouldThrow<IllegalArgumentException> { connection(baseUrl = url) } } }
        listOf("http://sim.example.test", "https://sim.example.test/", "https://user@sim.example.test").forEach { url ->
            withClue(url) { shouldThrow<IllegalArgumentException> { connection(customerBase = url) } }
        }
        connection(baseUrl = "https://sim-api.example.test:8443").baseUrl shouldBe "https://sim-api.example.test:8443"
        connection(baseUrl = "http://127.0.0.1:18080").baseUrl shouldBe "http://127.0.0.1:18080"
    }

    test("review #2 n-4: loopback http for the server URL is refused unless the explicit local/test flag is set (start-up fails without it)") {
        listOf("http://127.0.0.1:18080", "http://localhost:18080").forEach { url ->
            withClue(url) { shouldThrow<IllegalArgumentException> { connection(baseUrl = url, allowLoopbackHttp = false) } }
        }
        connection(baseUrl = "https://sim-api.example.test", allowLoopbackHttp = false).baseUrl shouldBe "https://sim-api.example.test"
        shouldThrow<IllegalArgumentException> { connection(baseUrl = "http://evil.example.test", allowLoopbackHttp = true) }
    }

    // INST-CODE-001 IC-6(D-INST-CODE-001): 카드사(발급사) 코드는 기관 식별 코드 공통 형식 ^[A-Z0-9]{1,32}$ — 하이픈·밑줄·소문자·33자는 기동 실패.
    test("start-up is refused when a card company code is not the common institution code format (hyphen, underscore, lower case, 33 characters)") {
        fun withCompany(code: String) = AuthenticationSettings(
            mapOf("TEST" to AuthenticationSettings.Backend("SIMULATOR", "sim-auth-demo")),
            mapOf("sim-auth-demo" to AuthenticationSettingsFixture.profile().let { AuthenticationSettings.Profile(it.version, listOf(AuthenticationSettings.CardCompany(code, "가상카드")), it.planPolicy) }),
            connection(),
        )
        listOf("SIMISS-A", "SIM_ISS_A", "simissa", "A".repeat(33), "").forEach { code ->
            withClue(code) { shouldThrow<IllegalArgumentException> { withCompany(code) } }
        }
        withCompany("A".repeat(32)).profileFor(Environment.TEST)!!.allows("A".repeat(32)) shouldBe true
    }

    test("toString never prints the client key") {
        settings().toString() shouldNotContain AuthenticationSettingsFixture.CLIENT_KEY
        connection().toString() shouldNotContain AuthenticationSettingsFixture.CLIENT_KEY
    }
})

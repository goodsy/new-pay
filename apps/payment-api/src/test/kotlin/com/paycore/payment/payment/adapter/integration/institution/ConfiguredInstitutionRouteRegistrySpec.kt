package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

/**
 * 기관 Route 등록부와 기동 격리(AUTH-001 §5.6 겹 2·§5.8): TEST → SIMULATOR만, REAL·모르는 type·LIVE SIMULATOR·loopback/허용 목록 밖 host·다른 프로토콜 버전·짧은 HMAC 키 →
 * 기동 거부. 모르는 routeRef는 fail-closed(null). Capability는 Demo(통화 [KRW] — D-AUTH1-002, cardType [CREDIT, CHECK]·할부 신용만 — D-AUTH1-003).
 */
class ConfiguredInstitutionRouteRegistrySpec : BehaviorSpec({

    Given("a valid TEST simulator route") {
        val registry = VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9))
        Then("the route profile carries the protocol version, profile version and capabilities; an unknown routeRef resolves to nothing") {
            val p = registry.resolve("SIM-VAN-01")!!
            p.institution shouldBe "SIMVAN"
            p.protocolVersion shouldBe "sim-van-1"
            p.profileVersion shouldBe "sim-van-a-demo-1"
            p.capabilities.confirmsNoEffect shouldBe true
            p.capabilities.supportedCurrencies shouldBe setOf(CurrencyCode("KRW"))
            p.capabilities.supportedCardTypes shouldBe setOf(CardType.CREDIT, CardType.CHECK)
            p.capabilities.installmentCardTypes shouldBe setOf(CardType.CREDIT)
            p.capabilities.installmentMonths shouldBe setOf(2, 3, 6)
            p.capabilities.closableAfter shouldBe Duration.ofMinutes(2)
            registry.resolve("SIM-VAN-99") shouldBe null
        }
        Then("its text form does not carry the HMAC key") {
            registry.toString() shouldNotContain java.util.Base64.getEncoder().encodeToString(StubVan.KEY)
        }
    }

    Given("startup isolation (§5.6 layer 2)") {
        Then("a REAL or unknown backend type, a LIVE simulator backend, or a route without a backend for its environment is refused") {
            mapOf(
                "REAL type" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9), backendType = "REAL") },
                "unknown type" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9), backendType = "MOCK") },
                "LIVE simulator" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, environment = "LIVE"), backendEnvironment = "LIVE") },
                "LIVE route without backend" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, environment = "LIVE")) },
                "unknown environment" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, environment = "PROD"), backendEnvironment = "PROD") },
            ).forEach { (name, build) -> withClue(name) { shouldThrow<IllegalArgumentException> { build() } } }
        }
        Then("a host outside loopback and the allow list, another protocol version or a short HMAC key is refused") {
            mapOf(
                "public host" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, host = "van.example.com")) },
                "protocol" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, protocolVersion = "sim-van-2")) },
                "short key" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, keyBase64 = "c2hvcnQ=")) },
                "no retry budget sign" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, connectRetryBudget = -1)) },
                // D-AUTH1-007: profile version은 Attempt 행(route_profile_version VARCHAR(64))에 고정되는 비교 키 — 1..64 printable ASCII, 기본값 없음.
                "blank profile version" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, profileVersion = " ")) },
                "profile version over 64" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, profileVersion = "v".repeat(65))) },
                "profile version with a space" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, profileVersion = "a demo")) },
                "deadline shorter than read" to {
                    VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9, readTimeout = Duration.ofSeconds(3), totalDeadline = Duration.ofSeconds(1)))
                },
            ).forEach { (name, build) -> withClue(name) { shouldThrow<IllegalArgumentException> { build() } } }
            ConfiguredInstitutionRouteRegistry(
                backends = mapOf("TEST" to ConfiguredInstitutionRouteRegistry.Backend("SIMULATOR")),
                routes = mapOf("SIM-VAN-01" to VanRouteFixture.route(9, host = "simulator.internal")),
                allowedHosts = listOf("simulator.internal"),
            ).resolve("SIM-VAN-01")!!.routeRef shouldBe "SIM-VAN-01"
        }
    }
})

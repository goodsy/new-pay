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
        val registry = VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9))
        Then("the route profile carries the protocol version, profile version and capabilities; an unknown routeRef resolves to nothing") {
            val p = registry.resolve("SIMVAN01")!!
            p.institution shouldBe "SIMVAN"
            p.protocolVersion shouldBe "sim-van-1"
            p.profileVersion shouldBe "sim-van-a-demo-1"
            p.capabilities.confirmsNoEffect shouldBe true
            p.capabilities.supportedCurrencies shouldBe setOf(CurrencyCode("KRW"))
            p.capabilities.supportedCardTypes shouldBe setOf(CardType.CREDIT, CardType.CHECK)
            p.capabilities.installmentCardTypes shouldBe setOf(CardType.CREDIT)
            p.capabilities.installmentMonths shouldBe setOf(2, 3, 6)
            p.capabilities.closableAfter shouldBe Duration.ofMinutes(2)
            registry.resolve("SIMVAN99") shouldBe null
        }
        Then("its text form does not carry the HMAC key") {
            registry.toString() shouldNotContain java.util.Base64.getEncoder().encodeToString(StubVan.KEY)
        }
    }

    Given("startup isolation (§5.6 layer 2)") {
        Then("a REAL or unknown backend type, a LIVE simulator backend, or a route without a backend for its environment is refused") {
            mapOf(
                "REAL type" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9), backendType = "REAL") },
                "unknown type" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9), backendType = "MOCK") },
                "LIVE simulator" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, environment = "LIVE"), backendEnvironment = "LIVE") },
                "LIVE route without backend" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, environment = "LIVE")) },
                "unknown environment" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, environment = "PROD"), backendEnvironment = "PROD") },
            ).forEach { (name, build) -> withClue(name) { shouldThrow<IllegalArgumentException> { build() } } }
        }
        Then("a host outside loopback and the allow list, another protocol version or a short HMAC key is refused") {
            mapOf(
                "public host" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, host = "van.example.com")) },
                "protocol" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, protocolVersion = "sim-van-2")) },
                "short key" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, keyBase64 = "c2hvcnQ=")) },
                "no retry budget sign" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, connectRetryBudget = -1)) },
                // D-AUTH1-007: profile version은 Attempt 행(route_profile_version VARCHAR(64))에 고정되는 비교 키 — 1..64 printable ASCII, 기본값 없음.
                "blank profile version" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, profileVersion = " ")) },
                "profile version over 64" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, profileVersion = "v".repeat(65))) },
                "profile version with a space" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, profileVersion = "a demo")) },
                "deadline shorter than read" to {
                    VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, readTimeout = Duration.ofSeconds(3), totalDeadline = Duration.ofSeconds(1)))
                },
            ).forEach { (name, build) -> withClue(name) { shouldThrow<IllegalArgumentException> { build() } } }
            // INST-CODE-001 IC-6(D-INST-CODE-001): Route 키·VAN 기관 코드는 기관 식별 코드 공통 형식 ^[A-Z0-9]{1,32}$.
            mapOf(
                "hyphenated route" to { VanRouteFixture.registry("SIM-VAN-01" to VanRouteFixture.route(9)) },
                "underscore route" to { VanRouteFixture.registry("SIM_VAN_01" to VanRouteFixture.route(9)) },
                "lower-case route" to { VanRouteFixture.registry("simvan01" to VanRouteFixture.route(9)) },
                "dotted route" to { VanRouteFixture.registry("SIMVAN.01" to VanRouteFixture.route(9)) },
                "route over 32" to { VanRouteFixture.registry("R".repeat(33) to VanRouteFixture.route(9)) },
                "hyphenated institution" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, institution = "SIM-VAN")) },
                "underscore institution" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, institution = "SIM_VAN")) },
            ).forEach { (name, build) -> withClue(name) { shouldThrow<IllegalArgumentException> { build() } } }
            VanRouteFixture.registry("R".repeat(32) to VanRouteFixture.route(9)).resolve("R".repeat(32))!!.routeRef shouldBe "R".repeat(32)
            ConfiguredInstitutionRouteRegistry(
                backends = mapOf("TEST" to ConfiguredInstitutionRouteRegistry.Backend("SIMULATOR")),
                routes = mapOf("SIMVAN01" to VanRouteFixture.route(9, host = "simulator.internal")),
                allowedHosts = listOf("simulator.internal"),
            ).resolve("SIMVAN01")!!.routeRef shouldBe "SIMVAN01"
        }
    }

    // CANCEL-001 §6.6 CN-28: 프로토콜 집합 {sim-van-1, sim-van-2}. 취소 Capability는 sim-van-2 Route에만(없으면 기동 실패), sim-van-1에 cancel 블록은 거부(fail-closed —
    // 취소를 보낼 수 없는 Route에 취소 Capability가 있으면 시작 판정이 Capability를 믿게 된다).
    Given("a sim-van-2 route with a cancel capability block") {
        val registry = VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.cancelRoute(9))
        Then("the profile carries the cancel capability; cancel start resolves it with its version (D-CANCEL1-004)") {
            val p = registry.resolve("SIMVAN01")!!
            p.protocolVersion shouldBe "sim-van-2"
            val c = p.capabilities.cancel!!
            c.version shouldBe "cancel-a-demo-1"
            c.confirmsNoEffect shouldBe true
            c.closableAfter shouldBe Duration.ofMinutes(2)
            c.inquiryRetention shouldBe Duration.ofDays(7)
            p.cancelCapability() shouldBe com.paycore.payment.payment.domain.CancelCapabilityResolution.Resolved(
                com.paycore.payment.payment.domain.CancelCapability("cancel-a-demo-1", setOf(CardType.CREDIT, CardType.CHECK), setOf(CardType.CREDIT)),
            )
        }
        Then("a sim-van-1 route has no cancel capability (cancel start is Unavailable — §6.1)") {
            VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9)).resolve("SIMVAN01")!!.cancelCapability() shouldBe
                com.paycore.payment.payment.domain.CancelCapabilityResolution.Unavailable
            // 변이 Mp11(생존 → 시험 추가): 등록부 밖에서 만든 프로파일도 sim-van-1이면 취소 블록이 있어도 Unavailable(취소 메시지가 없는 계약 — 방어 겹).
            val v2 = registry.resolve("SIMVAN01")!!
            v2.copy(protocolVersion = "sim-van-1").cancelCapability() shouldBe com.paycore.payment.payment.domain.CancelCapabilityResolution.Unavailable
        }
        Then("m-4: the route serves an original only with the same institution and environment") {
            val p = registry.resolve("SIMVAN01")!!
            val original = com.paycore.payment.payment.domain.ProviderRequestRef(
                "SIMVAN", com.paycore.payment.payment.domain.Environment.TEST, "SIMMERCH0001", "AUTHORIZE", "ar_01JA0000000000000000000001",
            )
            p.servesOriginal(original) shouldBe true
            p.servesOriginal(original.copy(institution = "OTHERVAN")) shouldBe false
            p.servesOriginal(original.copy(environment = com.paycore.payment.payment.domain.Environment.LIVE)) shouldBe false
        }
        Then("startup refuses an inconsistent cancel block or protocol") {
            val c = VanRouteFixture.cancelCapabilities()
            mapOf(
                "sim-van-2 without cancel block" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, protocolVersion = "sim-van-2")) },
                "sim-van-1 with cancel block" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, cancel = c)) },
                "unknown protocol" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.route(9, protocolVersion = "sim-van-3", cancel = c)) },
                "blank cancel version" to { VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.cancelRoute(9, VanRouteFixture.cancelCapabilities(version = " "))) },
                "cancel version over 64" to {
                    VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.cancelRoute(9, VanRouteFixture.cancelCapabilities(version = "v".repeat(65))))
                },
                "closable after not before retention" to {
                    VanRouteFixture.registry(
                        "SIMVAN01" to VanRouteFixture.cancelRoute(9, VanRouteFixture.cancelCapabilities(closableAfter = Duration.ofDays(7), inquiryRetention = Duration.ofDays(7))),
                    )
                },
                "zero closable after" to {
                    VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.cancelRoute(9, VanRouteFixture.cancelCapabilities(closableAfter = Duration.ZERO)))
                },
                "unknown partial card type" to {
                    VanRouteFixture.registry("SIMVAN01" to VanRouteFixture.cancelRoute(9, VanRouteFixture.cancelCapabilities(partialLumpSumCardTypes = listOf("PREPAID"))))
                },
                "partial installment card type without installment support" to {
                    VanRouteFixture.registry(
                        "SIMVAN01" to VanRouteFixture.cancelRoute(9, VanRouteFixture.cancelCapabilities(partialInstallmentCardTypes = listOf("CHECK"))),
                    )
                },
            ).forEach { (name, build) -> withClue(name) { shouldThrow<IllegalArgumentException> { build() } } }
        }
    }
})

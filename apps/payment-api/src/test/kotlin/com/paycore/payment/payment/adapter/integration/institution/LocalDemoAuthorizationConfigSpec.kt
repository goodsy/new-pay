package com.paycore.payment.payment.adapter.integration.institution

import com.paycore.payment.payment.adapter.batch.AuthorizationRecoveryJobConfiguration
import com.paycore.payment.payment.adapter.batch.CancellationRecoveryJobConfiguration
import com.paycore.payment.payment.adapter.batch.CancellationRecoveryJobSettings
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CurrencyCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.Duration
import java.util.Base64

/**
 * 로컬 Demo 설정 예시(`.env.example`, AUTH-001 §16.17 발견 3·§20-3): payment-api `paycore.authorization.*` 예시(`PAYCORE_PAYMENT_AUTHORIZATION_JSON` — payment-api 기동 때만
 * `SPRING_APPLICATION_JSON`으로 넘긴다)가 실제 설정 클래스 바인딩과 기동 검사(Route 등록부·시간 설정·AU-34·m-2)를 통과하고, 가상 VAN Simulator 변수와 짝(§20-6: host·port·key-id·키·
 * closableAfter·보존기간·신용 할부 개월)이 맞는지 본다. 비어 있는 Simulator 변수(포트·HMAC 키)는 개발자가 채우는 값이라 시험 전용 값으로 채운다(운영 키 아님).
 * 할부 개월 기대값은 시험 Route·결제창 Plan Demo([2, 3, 6] — 시험 yml)에서 직접 적는다.
 *
 * CANCEL-001 S8(§20): Route가 `sim-van-2`·취소 Capability·**새 profile-version**으로 바뀌고 `paycore.cancellation.timing.*`(CN-29)이 같은 JSON에 있다 — 취소 시간 설정·회복 trigger 바인딩,
 * 취소 기동 검사(D-S4-2), 취소 Capability = Simulator 프로파일 A 짝(취소 무효과 확정·closableAfter·보존기간 변수, 부분취소 카드 종류 = Simulator yml A의 Demo 고정값)을 함께 본다.
 */
class LocalDemoAuthorizationConfigSpec : BehaviorSpec({

    val envExample = generateSequence(File(".").absoluteFile) { it.parentFile }.map { File(it, ".env.example") }.first { it.isFile }
    val env: Map<String, String> = envExample.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && it.contains('=') }
        .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().removeSurrounding("'") }

    fun flatten(prefix: String, node: JsonNode, out: MutableMap<String, String>) {
        when {
            node.isObject -> node.properties().forEach { (k, v) -> flatten(if (prefix.isEmpty()) k else "$prefix.$k", v, out) }
            node.isArray -> node.forEachIndexed { i, v -> flatten("$prefix[$i]", v, out) }
            node.isString -> out[prefix] = node.stringValue()
            else -> out[prefix] = node.toString()
        }
    }

    // 개발자가 채우는 빈 Simulator 변수의 시험 전용 값(운영 키 아님).
    val filled = mapOf(
        "PAYCORE_SIMULATOR_VAN_PORT" to "17001",
        "PAYCORE_SIMULATOR_VAN_HMAC_KEY_BASE64" to Base64.getEncoder().encodeToString("local-demo-test-only-sim-van-key-000001".toByteArray()),
    )

    Given("the payment-api authorization example in .env.example (AUTH-001 §20-3)") {
        Then("it binds to the real settings and passes the registry, timing and AU-34/m-2 startup checks, paired with the Simulator VAN variables (§20-6)") {
            val json = withClue("PAYCORE_PAYMENT_AUTHORIZATION_JSON in ${envExample.path}") { env["PAYCORE_PAYMENT_AUTHORIZATION_JSON"].shouldNotBeNull() }
            val props = mutableMapOf<String, String>()
            flatten("", ObjectMapper().readTree(json), props)
            val simulatorVars = env.filterKeys { it.startsWith("PAYCORE_SIMULATOR_VAN_") }.mapValues { (k, v) -> filled[k] ?: v }

            ApplicationContextRunner()
                .withUserConfiguration(
                    InstitutionRouteRegistryConfiguration::class.java, AuthorizationTimingConfiguration::class.java, AuthorizationTimingConsistency::class.java,
                    AuthorizationRecoveryJobConfiguration::class.java,
                    CancellationTimingConfiguration::class.java, CancellationTimingConsistency::class.java, CancellationRecoveryJobConfiguration::class.java,
                )
                .withPropertyValues(*(props + simulatorVars).map { (k, v) -> "$k=$v" }.toTypedArray())
                .run { context ->
                    withClue(context.startupFailure) { context.startupFailure shouldBe null }
                    val registry = context.getBean(ConfiguredInstitutionRouteRegistry::class.java)
                    val profile = registry.resolve("SIMVAN01").shouldNotBeNull()
                    val route = registry.connection("SIMVAN01").shouldNotBeNull()
                    route.port shouldBe 17001
                    route.keyId shouldBe env["PAYCORE_SIMULATOR_VAN_KEY_ID"]
                    route.host shouldBe "127.0.0.1"
                    route.key.contentEquals(Base64.getDecoder().decode(filled["PAYCORE_SIMULATOR_VAN_HMAC_KEY_BASE64"])) shouldBe true
                    profile.capabilities.confirmsNoEffect shouldBe true
                    profile.capabilities.closableAfter shouldBe Duration.parse(env["PAYCORE_SIMULATOR_VAN_CLOSABLE_AFTER"])
                    profile.capabilities.inquiryRetention shouldBe Duration.parse(env["PAYCORE_SIMULATOR_VAN_INQUIRY_RETENTION"])
                    profile.capabilities.supportedCurrencies shouldBe setOf(CurrencyCode("KRW"))
                    profile.capabilities.installmentCardTypes shouldBe setOf(CardType.CREDIT)
                    profile.capabilities.installmentMonths shouldBe setOf(2, 3, 6)
                    profile.capabilities.installmentMinimumAmount shouldBe 50_000L
                    context.getBean(AuthorizationTimingSettings::class.java).recoveryConfigVersion.isNotBlank() shouldBe true
                    // CANCEL-001 S8: sim-van-2 + 취소 Capability(Simulator 프로파일 A 짝) + 취소 timing.
                    profile.protocolVersion shouldBe "sim-van-2"
                    (profile.profileVersion != "sim-van-a-demo-1") shouldBe true // Route 모양이 바뀌면 profile-version도 바뀐다(CN-28, §20)
                    val cancel = profile.capabilities.cancel.shouldNotBeNull()
                    cancel.version.isNotBlank() shouldBe true
                    cancel.confirmsNoEffect shouldBe true
                    cancel.closableAfter shouldBe Duration.parse(env["PAYCORE_SIMULATOR_VAN_CANCEL_CLOSABLE_AFTER"])
                    cancel.inquiryRetention shouldBe Duration.parse(env["PAYCORE_SIMULATOR_VAN_CANCEL_INQUIRY_RETENTION"])
                    cancel.partialLumpSumCardTypes shouldBe setOf(CardType.CREDIT, CardType.CHECK)
                    cancel.partialInstallmentCardTypes shouldBe setOf(CardType.CREDIT)
                    (profile.cancelCapability() is com.paycore.payment.payment.domain.CancelCapabilityResolution.Resolved) shouldBe true
                    context.getBean(CancellationTimingSettings::class.java).recoveryConfigVersion.isNotBlank() shouldBe true
                    context.getBean(CancellationRecoveryJobSettings::class.java).enabled shouldBe true
                }
        }
        Then("the Simulator VAN Demo credit installment months equal the PG route and checkout Plan Demo months [2, 3, 6] (§16.17 finding 2)") {
            env["PAYCORE_SIMULATOR_VAN_CREDIT_INSTALLMENT_MONTHS"]!!.split(',').map { it.trim().toInt() }.toSet() shouldBe setOf(2, 3, 6)
        }
    }
})

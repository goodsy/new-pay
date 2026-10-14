package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.adapter.integration.EntitlementSettingsConfiguration
import com.paycore.payment.entitlement.adapter.integration.MicrometerEntitlementMetrics
import com.paycore.payment.entitlement.adapter.web.EntitlementInboxConfiguration
import com.paycore.payment.entitlement.application.provided.EntitlementFreshnessQuery
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapAccount
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * PAYMENT 권리 지표·신선도 조회(명세 §11 Observability)와 설정의 fail-closed 기동(명세 §8 — 운영 기본값 없음).
 * 지표 label은 분류값만(outcome·result·reason) — MID·merchantId를 label로 쓰지 않는다.
 */
@EntitlementDbTest
class EntitlementObservabilitySpec(
    private val context: WebApplicationContext,
    private val registry: MeterRegistry,
    private val freshness: EntitlementFreshnessQuery,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()

    fun count(name: String, vararg tags: String): Double = registry.find(name).tags(*tags).counter()?.count() ?: 0.0

    Given("inbox deliveries") {
        Then("each stored outcome is counted by outcome label; an integrity conflict raises the blocked-head gauge and freshness count") {
            val applied = count(MicrometerEntitlementMetrics.INBOX_OUTCOMES, "outcome", "APPLIED")
            val conflicts = count(MicrometerEntitlementMetrics.INBOX_OUTCOMES, "outcome", "INTEGRITY_CONFLICT")
            val blockedBefore = freshness.overview().blockedHeads
            val content = SnapshotFixture.entitled()
            send(mvc, body(content, SnapshotFixture.leaseAt(T0))).status shouldBe 200
            send(mvc, body(content.mapAccount { it.copy(blocked = true) }, SnapshotFixture.leaseAt(T0))).status shouldBe 200
            (count(MicrometerEntitlementMetrics.INBOX_OUTCOMES, "outcome", "APPLIED") - applied) shouldBe 1.0
            (count(MicrometerEntitlementMetrics.INBOX_OUTCOMES, "outcome", "INTEGRITY_CONFLICT") - conflicts) shouldBe 1.0
            freshness.overview().blockedHeads shouldBe blockedBefore + 1
            registry.get(MicrometerEntitlementMetrics.BLOCKED_HEADS).gauge().value() shouldBe (blockedBefore + 1).toDouble()
            registry.find(MicrometerEntitlementMetrics.INBOX_OUTCOMES).counters().flatMap { c -> c.id.tags.map { it.key } }.toSet() shouldBe setOf("outcome")
        }
    }

    Given("entitlement settings") {
        Then("the production application.yml carries no skew margin or signing key, and without values the context does not start") {
            val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).single()
            listOf("paycore.payment.entitlement.clock-skew-margin", "paycore.payment.entitlement.inbox.signing-keys[0].secret").map { yaml.getProperty(it) } shouldBe
                listOf(null, null)
            listOf(EntitlementSettingsConfiguration::class.java, EntitlementInboxConfiguration::class.java).map { config ->
                ApplicationContextRunner().withUserConfiguration(config).run { (it.startupFailure != null) shouldBe true }
            }
        }

        Then("a too-short signing key or three keys at once fail startup; one valid key starts") {
            val key = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh"
            fun run(vararg props: String) = ApplicationContextRunner().withUserConfiguration(EntitlementInboxConfiguration::class.java)
                .withPropertyValues(*props)
            run("paycore.payment.entitlement.inbox.signing-keys[0].key-id=k1", "paycore.payment.entitlement.inbox.signing-keys[0].secret=c2hvcnQ=")
                .run { (it.startupFailure != null) shouldBe true }
            run(
                *(0..2).flatMap { listOf("paycore.payment.entitlement.inbox.signing-keys[$it].key-id=k$it", "paycore.payment.entitlement.inbox.signing-keys[$it].secret=$key") }
                    .toTypedArray(),
            ).run { (it.startupFailure != null) shouldBe true }
            run("paycore.payment.entitlement.inbox.signing-keys[0].key-id=k1", "paycore.payment.entitlement.inbox.signing-keys[0].secret=$key")
                .run { it.startupFailure shouldBe null }
        }
    }
})

package com.paycore.financial.acquiring.adapter.web.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import java.time.Duration

/**
 * ACQ-VIEW-001 §3.1·AC 2: admin-channel 설정 기본값 없음·형식 검사, **채널별 별도 키**(결제 사실 Inbox 키와 kid·바이트 분리 — 교체 중 2키 각각, Binder로 속성만 읽음),
 * context path 금지, permit 대기 0. 실패 메시지에 키·kid 원문이 없다.
 */
class FinancialAdminChannelSettingsSpec : BehaviorSpec({

    val admin1 = FinancialAdminChannelFixture.secret(FinancialAdminChannelFixture.K1)
    val admin2 = FinancialAdminChannelFixture.secret(FinancialAdminChannelFixture.K2)
    val inboxSecret = "dGVzdC1vbmx5LXBheW1lbnQtZmFjdC1nb2xkZW4ta2V5LTAwMDE="
    val inboxSecond = "dGVzdC1vbmx5LXBheW1lbnQtZmFjdC1nb2xkZW4ta2V5LTAwMDI="

    fun inbox(vararg keys: Pair<String, String>): Binder = Binder(
        MapConfigurationPropertySource(
            keys.withIndex().flatMap { (i, k) ->
                listOf(
                    "paycore.financial.payment-fact-inbox.signing-keys[$i].key-id" to k.first,
                    "paycore.financial.payment-fact-inbox.signing-keys[$i].secret" to k.second,
                )
            }.toMap(),
        ),
    )

    Given("admin-channel keys and the payment-fact inbox keys (separate key spaces per channel)") {
        Then("distinct ids and bytes are accepted (one and two keys on each side)") {
            AdminChannelKeySeparation(FinancialAdminChannelFixture.settings(), inbox("fact-k1" to inboxSecret, "fact-k2" to inboxSecond))
            AdminChannelKeySeparation(FinancialAdminChannelFixture.settings(listOf(FinancialAdminChannelFixture.K1)), inbox("fact-k1" to inboxSecret))
        }
        Then("the same key bytes under another id are refused, also when only a rotating key on either side matches") {
            shouldThrow<IllegalStateException> { AdminChannelKeySeparation(FinancialAdminChannelFixture.settings(), inbox("fact-k1" to admin1)) }
            shouldThrow<IllegalStateException> { AdminChannelKeySeparation(FinancialAdminChannelFixture.settings(), inbox("fact-k1" to inboxSecret, "fact-k2" to admin2)) }
        }
        Then("the same key id with different bytes is refused, and the messages name the rule only (no kid, no key)") {
            val id = shouldThrow<IllegalStateException> {
                AdminChannelKeySeparation(FinancialAdminChannelFixture.settings(), inbox(FinancialAdminChannelFixture.K2 to inboxSecret))
            }
            id.message!! shouldNotContain FinancialAdminChannelFixture.K2
            id.message!! shouldNotContain inboxSecret
            val bytes = shouldThrow<IllegalStateException> { AdminChannelKeySeparation(FinancialAdminChannelFixture.settings(), inbox("fact-k1" to admin1)) }
            bytes.message!! shouldNotContain admin1
        }
    }

    Given("settings validation (no defaults, Demo values are not operating values)") {
        val key = AdminChannelSettings.SigningKey(FinancialAdminChannelFixture.K1, admin1)
        Then("key count, id format, key length, window range/whole seconds and permits (1..16) are enforced") {
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(emptyList(), Duration.ofSeconds(300), 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key, key, key), Duration.ofSeconds(300), 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(AdminChannelSettings.SigningKey("bad id", admin1)), Duration.ofSeconds(300), 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(AdminChannelSettings.SigningKey("k", "c2hvcnQ=")), Duration.ofSeconds(300), 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofMinutes(11), 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofMillis(1500), 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 0) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 17) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings.SigningKey("k", "not base64 !") }.message!! shouldNotContain "not base64"
        }
        Then("toString never shows a secret") {
            AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 2).toString() shouldNotContain admin1
            key.toString() shouldNotContain admin1
        }
        Then("a context path is refused; the permits answer busy without waiting when exhausted and count it per endpoint") {
            shouldThrow<IllegalStateException> { AdminChannelContextPathGuard("/") }
            AdminChannelContextPathGuard("")
            val registry = SimpleMeterRegistry()
            val permits = AdminChannelPermits(FinancialAdminChannelFixture.settings(readPermits = 1), registry)
            permits.read("acquisition_file_detail", { "busy" }) { permits.read("acquisition_file_detail", { "busy" }) { "inner" } } shouldBe "busy"
            registry.counter(AdminChannelPermits.BUSY_METRIC, "endpoint", "acquisition_file_detail").count() shouldBe 1.0
            permits.read("acquisition_file_detail", { "busy" }) { "ok" } shouldBe "ok"
        }
    }

    Given("the Spring configuration (startup refusal)") {
        val base = arrayOf(
            "paycore.financial.admin-channel.clock-window=PT300S",
            "paycore.financial.admin-channel.read-permits=2",
            "paycore.financial.admin-channel.signing-keys[0].key-id=${FinancialAdminChannelFixture.K1}",
            "paycore.financial.admin-channel.signing-keys[0].secret=$admin1",
        )
        val web = WebApplicationContextRunner().withUserConfiguration(AdminChannelConfiguration::class.java).withBean(SimpleMeterRegistry::class.java)
        Then("separate keys start a servlet web context") {
            web.withPropertyValues(
                *base, "paycore.financial.payment-fact-inbox.signing-keys[0].key-id=fact-k1", "paycore.financial.payment-fact-inbox.signing-keys[0].secret=$inboxSecret",
            ).run { it.startupFailure shouldBe null }
        }
        Then("a key shared with the payment-fact inbox channel does not start") {
            web.withPropertyValues(
                *base, "paycore.financial.payment-fact-inbox.signing-keys[0].key-id=fact-k1", "paycore.financial.payment-fact-inbox.signing-keys[0].secret=$admin1",
            ).run { it.startupFailure.shouldNotBeNull() }
        }
        Then("missing admin-channel settings do not start (no default, I-8), and a context path does not start") {
            web.run { it.startupFailure.shouldNotBeNull() }
            web.withPropertyValues(*base, "server.servlet.context-path=/financial").run { it.startupFailure.shouldNotBeNull() }
        }
    }
})

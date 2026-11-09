package com.paycore.master.merchant.adapter.web.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import java.time.Duration

/**
 * MERCHANT-OPS-001 §3·AC 2: admin-channel 설정 기본값 없음·형식 검사, 권리 dispatch 키와 kid·바이트 분리(Binder — integration 타입 비의존), context path 금지.
 * 설정 구성은 서블릿 웹 컨텍스트에서만(비웹 1회성 명령은 admin-channel 키 없이 기동 — DEMO-SEED-001). 실패 메시지에 키·kid 원문이 없다.
 */
class MasterAdminChannelSettingsSpec : BehaviorSpec({

    val admin1 = MasterAdminChannelFixture.secret(MasterAdminChannelFixture.K1)
    val dispatchSecret = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh"

    fun dispatch(kid: String, secret: String): Binder = Binder(
        MapConfigurationPropertySource(
            mapOf("paycore.master.entitlement.dispatch.signing-key-id" to kid, "paycore.master.entitlement.dispatch.signing-key" to secret),
        ),
    )

    Given("admin-channel keys and the entitlement dispatch key") {
        Then("distinct ids and bytes are accepted (one and two admin keys)") {
            AdminChannelKeySeparation(MasterAdminChannelFixture.settings(), dispatch("test-k1", dispatchSecret))
            AdminChannelKeySeparation(MasterAdminChannelFixture.settings(listOf(MasterAdminChannelFixture.K1)), dispatch("test-k1", dispatchSecret))
        }
        Then("the same key bytes under another id are refused, also when only the second (rotating) admin key matches") {
            shouldThrow<IllegalStateException> { AdminChannelKeySeparation(MasterAdminChannelFixture.settings(), dispatch("test-k1", admin1)) }
            shouldThrow<IllegalStateException> {
                AdminChannelKeySeparation(MasterAdminChannelFixture.settings(), dispatch("test-k1", MasterAdminChannelFixture.secret(MasterAdminChannelFixture.K2)))
            }
        }
        Then("the same key id with different bytes is refused, and the message names the rule only") {
            val e = shouldThrow<IllegalStateException> { AdminChannelKeySeparation(MasterAdminChannelFixture.settings(), dispatch(MasterAdminChannelFixture.K2, dispatchSecret)) }
            e.message!! shouldNotContain MasterAdminChannelFixture.K2
            e.message!! shouldNotContain dispatchSecret
            val bytes = shouldThrow<IllegalStateException> { AdminChannelKeySeparation(MasterAdminChannelFixture.settings(), dispatch("test-k1", admin1)) }
            bytes.message!! shouldNotContain admin1
        }
    }

    Given("settings validation (no defaults, Demo values are not operating values)") {
        val key = AdminChannelSettings.SigningKey(MasterAdminChannelFixture.K1, admin1)
        Then("key count, id format, key length, window range/whole seconds and permits are enforced") {
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(emptyList(), Duration.ofSeconds(300), 4, 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key, key, key), Duration.ofSeconds(300), 4, 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(AdminChannelSettings.SigningKey("bad id", admin1)), Duration.ofSeconds(300), 4, 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(AdminChannelSettings.SigningKey("k", "c2hvcnQ=")), Duration.ofSeconds(300), 4, 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofMinutes(11), 4, 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofMillis(1500), 4, 2) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 0, 2) }
            // MERCHANT-OPS-002 §3.1 C-1: 명령 permit 1..16(대기 0 — Demo 2).
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 4, 0) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 4, 17) }
            AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 4, 16).writePermits shouldBe 16
            shouldThrow<IllegalArgumentException> { AdminChannelSettings.SigningKey("k", "not base64 !") }.message!! shouldNotContain "not base64"
        }
        Then("toString never shows a secret") {
            AdminChannelSettings(listOf(key), Duration.ofSeconds(300), 4, 2).toString() shouldNotContain admin1
            key.toString() shouldNotContain admin1
        }
        Then("a context path is refused; the permits answer busy without waiting when exhausted") {
            shouldThrow<IllegalStateException> { AdminChannelContextPathGuard("/") }
            AdminChannelContextPathGuard("")
            val registry = SimpleMeterRegistry()
            val permits = AdminChannelPermits(MasterAdminChannelFixture.settings(readPermits = 1), registry)
            permits.read("detail", { "busy" }) { permits.read("detail", { "busy" }) { "inner" } } shouldBe "busy"
            registry.counter(AdminChannelPermits.BUSY_METRIC, "endpoint", "detail").count() shouldBe 1.0
            permits.read("detail", { "busy" }) { "ok" } shouldBe "ok"
        }
        Then("MERCHANT-OPS-002 C-1: the write permit is separate from the read permit and answers busy without waiting when exhausted") {
            val registry = SimpleMeterRegistry()
            val permits = AdminChannelPermits(MasterAdminChannelFixture.settings(readPermits = 1, writePermits = 1), registry)
            // 조회 permit을 모두 잡아도 명령은 들어가고, 명령 permit을 모두 잡아도 조회는 들어간다.
            permits.read("detail", { "busy" }) { permits.write("RegisterBusiness", { "busy" }) { "write-ok" } } shouldBe "write-ok"
            permits.write("RegisterBusiness", { "busy" }) { permits.read("detail", { "busy" }) { "read-ok" } } shouldBe "read-ok"
            permits.write("RegisterBusiness", { "busy" }) { permits.write("RegisterMerchant", { "busy" }) { "inner" } } shouldBe "busy"
            registry.counter(AdminChannelPermits.BUSY_METRIC, "endpoint", "RegisterMerchant").count() shouldBe 1.0
            registry.counter(AdminChannelPermits.BUSY_METRIC, "endpoint", "detail").count() shouldBe 0.0
        }
    }

    Given("the Spring configuration (startup refusal)") {
        val base = arrayOf(
            "paycore.master.admin-channel.clock-window=PT300S",
            "paycore.master.admin-channel.read-permits=4",
            "paycore.master.admin-channel.write-permits=2",
            "paycore.master.admin-channel.signing-keys[0].key-id=${MasterAdminChannelFixture.K1}",
            "paycore.master.admin-channel.signing-keys[0].secret=$admin1",
        )
        val web = WebApplicationContextRunner().withUserConfiguration(AdminChannelConfiguration::class.java).withBean(SimpleMeterRegistry::class.java)
        Then("separate keys start a servlet web context") {
            web.withPropertyValues(*base, "paycore.master.entitlement.dispatch.signing-key-id=test-k1", "paycore.master.entitlement.dispatch.signing-key=$dispatchSecret")
                .run { it.startupFailure shouldBe null }
        }
        Then("a key shared with the entitlement dispatch channel does not start") {
            web.withPropertyValues(*base, "paycore.master.entitlement.dispatch.signing-key-id=test-k1", "paycore.master.entitlement.dispatch.signing-key=$admin1")
                .run { it.startupFailure.shouldNotBeNull() }
        }
        Then("missing admin-channel settings do not start a web context (no default), and a context path does not start") {
            web.run { it.startupFailure.shouldNotBeNull() }
            web.withPropertyValues(*base, "server.servlet.context-path=/master").run { it.startupFailure.shouldNotBeNull() }
        }
        Then("MERCHANT-OPS-002 C-1: write-permits has no default — without it a servlet web context does not start; out of range does not start either") {
            val withoutWrite = base.filterNot { it.startsWith("paycore.master.admin-channel.write-permits") }.toTypedArray()
            withoutWrite.size shouldBe base.size - 1
            web.withPropertyValues(*withoutWrite).run { it.startupFailure.shouldNotBeNull() }
            web.withPropertyValues(*withoutWrite, "paycore.master.admin-channel.write-permits=17").run { it.startupFailure.shouldNotBeNull() }
            web.withPropertyValues(*base).run { it.startupFailure shouldBe null }
        }
        Then("a non-web context (one-shot command) needs no admin-channel settings") {
            ApplicationContextRunner().withUserConfiguration(AdminChannelConfiguration::class.java).run {
                it.startupFailure shouldBe null
                it.getBeansOfType(AdminChannelSettings::class.java).size shouldBe 0
            }
        }
    }
})

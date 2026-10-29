package com.paycore.payment.payment.adapter.web.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * ADMIN 전용 키 공간(ADMIN-PAYMENT-OPS-001 §4.2, D-OPS-001, I-04): admin-channel 키의 **바이트 또는 kid**가 MASTER 권리 채널(entitlement inbox) 키 중 하나와 같으면 기동 거부.
 * entitlement 키는 그 모듈 타입을 쓰지 않고 `Binder`로 속성만 읽는다(F-11). 교체 중 2키는 각각 검사한다. 기동 실패 메시지에 키 원문이 없다. 핵심 변이 "키 분리 제거"를 잡는다.
 */
class AdminChannelKeySeparationSpec : BehaviorSpec({

    val admin1 = AdminChannelFixture.secret(AdminChannelFixture.K1)
    val admin2 = AdminChannelFixture.secret(AdminChannelFixture.K2)
    val inboxSecret = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTEh"
    val inboxSecret2 = "dGVzdC1vbmx5LWVudGl0bGVtZW50LXNpZ25pbmcta2V5LTIh"

    fun inbox(vararg keys: Pair<String, String>): Binder = Binder(
        MapConfigurationPropertySource(
            keys.flatMapIndexed { i, (kid, secret) ->
                listOf("paycore.payment.entitlement.inbox.signing-keys[$i].key-id" to kid, "paycore.payment.entitlement.inbox.signing-keys[$i].secret" to secret)
            }.toMap(),
        ),
    )

    Given("admin-channel keys and entitlement inbox keys") {
        Then("distinct ids and bytes are accepted (one and two keys on each side)") {
            AdminChannelKeySeparation(AdminChannelFixture.settings(), inbox("test-k1" to inboxSecret, "test-k2" to inboxSecret2))
            AdminChannelKeySeparation(AdminChannelFixture.settings(listOf(AdminChannelFixture.K1)), inbox("test-k1" to inboxSecret))
        }
        Then("the same key bytes under a different id are refused, also when it is only the second (rotating) admin key") {
            shouldThrow<IllegalStateException> { AdminChannelKeySeparation(AdminChannelFixture.settings(), inbox("test-k1" to admin1)) }
            shouldThrow<IllegalStateException> { AdminChannelKeySeparation(AdminChannelFixture.settings(), inbox("test-k1" to inboxSecret, "test-k2" to admin2)) }
        }
        Then("the same key id with different bytes is refused") {
            shouldThrow<IllegalStateException> { AdminChannelKeySeparation(AdminChannelFixture.settings(), inbox(AdminChannelFixture.K2 to inboxSecret)) }
        }
        Then("the failure message names the rule, never a key") {
            val e = shouldThrow<IllegalStateException> { AdminChannelKeySeparation(AdminChannelFixture.settings(), inbox("test-k1" to admin1)) }
            e.message!! shouldNotContain admin1
            e.message!! shouldNotContain "test-k1"
        }
    }

    Given("the Spring configuration (startup refusal)") {
        val runner = ApplicationContextRunner().withUserConfiguration(AdminChannelConfiguration::class.java).withPropertyValues(
            "paycore.payment.admin-channel.clock-window=PT300S",
            "paycore.payment.admin-channel.signing-keys[0].key-id=${AdminChannelFixture.K1}",
            "paycore.payment.admin-channel.signing-keys[0].secret=$admin1",
        )
        Then("separate keys start") {
            runner.withPropertyValues(
                "paycore.payment.entitlement.inbox.signing-keys[0].key-id=test-k1", "paycore.payment.entitlement.inbox.signing-keys[0].secret=$inboxSecret",
            ).run { it.startupFailure shouldBe null }
        }
        Then("a shared key does not start") {
            runner.withPropertyValues(
                "paycore.payment.entitlement.inbox.signing-keys[0].key-id=test-k1", "paycore.payment.entitlement.inbox.signing-keys[0].secret=$admin1",
            ).run { it.startupFailure.shouldNotBeNull() }
        }
        Then("missing admin-channel keys do not start (no default)") {
            ApplicationContextRunner().withUserConfiguration(AdminChannelConfiguration::class.java).run { it.startupFailure.shouldNotBeNull() }
        }
    }
})

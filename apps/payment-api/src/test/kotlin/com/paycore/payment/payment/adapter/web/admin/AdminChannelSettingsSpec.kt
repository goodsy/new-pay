package com.paycore.payment.payment.adapter.web.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration
import java.util.Base64

/**
 * ADMIN 채널 설정(ADMIN-PAYMENT-OPS-001 §4.2 키 공간, D-OPS-001): 키 1..2개(교체 중 2개), kid 문법·중복 금지, Base64 ≥ 32바이트, 기본값 없음, `toString` 마스킹,
 * 시각 창은 설정값(Demo 300초 — 운영값 LIVE Gate)이고 1초..10분.
 */
class AdminChannelSettingsSpec : BehaviorSpec({

    val k1 = AdminChannelFixture.secret(AdminChannelFixture.K1)
    val k2 = AdminChannelFixture.secret(AdminChannelFixture.K2)
    fun key(id: String, secret: String = k1) = AdminChannelSettings.SigningKey(id, secret)
    val window = Duration.ofSeconds(300)

    Given("valid settings") {
        Then("one key or two keys while rotating are accepted, and each key is found by its id only") {
            AdminChannelSettings(listOf(key("a")), window).key("a") shouldBe Base64.getDecoder().decode(k1)
            val two = AdminChannelSettings(listOf(key("a"), key("b", k2)), window)
            two.key("b") shouldBe Base64.getDecoder().decode(k2)
            two.key("c") shouldBe null
        }
        Then("toString never shows a secret") {
            val s = AdminChannelSettings(listOf(key("a"), key("b", k2)), window)
            s.toString() shouldNotContain k1
            s.toString() shouldNotContain k2
            s.signingKeys.first().toString() shouldNotContain k1
        }
    }

    Given("invalid settings (startup must fail)") {
        Then("zero keys, three keys and duplicate key ids are refused") {
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(emptyList(), window) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key("a"), key("b", k2), key("c")), window) }
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key("a"), key("a", k2)), window) }
        }
        Then("a key id outside [A-Za-z0-9._-]{1,32} is refused") {
            listOf("", "a b", "a/b", "k".repeat(33)).forEach { shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key(it)), window) } }
        }
        Then("a secret that is not Base64 or shorter than 32 bytes is refused without echoing it") {
            val short = Base64.getEncoder().encodeToString(ByteArray(31) { 7 })
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key("a", short)), window) }.message!! shouldNotContain short
            val notBase64 = "not*base64*secret-value-0123456789"
            shouldThrow<IllegalArgumentException> { key("a", notBase64) }.let { e ->
                e.message!! shouldNotContain notBase64
                e.cause shouldBe null
            }
        }
        Then("a clock window outside 1s..10min is refused") {
            listOf(Duration.ZERO, Duration.ofMillis(999), Duration.ofSeconds(-1), Duration.ofMinutes(10).plusSeconds(1)).forEach {
                shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key("a")), it) }
            }
            AdminChannelSettings(listOf(key("a")), Duration.ofMinutes(10)).clockWindow shouldBe Duration.ofMinutes(10)
            // checkpoint n-1: 정수 초만.
            shouldThrow<IllegalArgumentException> { AdminChannelSettings(listOf(key("a")), Duration.ofMillis(300_500)) }
        }
    }
})

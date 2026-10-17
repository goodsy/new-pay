package com.paycore.payment.payment.adapter.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * 결제창 설정 기동 검사(AUTHN-001 §12.1 m-9): checkoutUrl·returnUrl origin은 설정에서만 오고 `https://host[:port]`만 허용(userinfo·path·query·fragment·http 거부), 수명은
 * ms 단위 정수·1초 이상, 정책 버전 1..64 출력 가능 ASCII. 위반은 바인딩 실패 = 기동 실패.
 */
class CheckoutSettingsSpec : FunSpec({

    fun settings(base: String = "https://checkout.example.test", ttl: Duration = Duration.ofMinutes(10), version: String = "checkout-demo-1") =
        CheckoutSettings(version, base, ttl, Duration.ofMinutes(20), Duration.ofMinutes(10))

    test("an https origin with an optional port and whole-millisecond TTLs is accepted") {
        settings().publicBaseUrl shouldBe "https://checkout.example.test"
        settings(base = "https://pay.example.test:8443").publicBaseUrl shouldBe "https://pay.example.test:8443"
    }

    test("origins that are not plain https host[:port] refuse start-up") {
        listOf(
            "http://checkout.example.test", "https://checkout.example.test/", "https://checkout.example.test/path", "https://user@checkout.example.test",
            "https://checkout.example.test?x=1", "https://checkout.example.test#f", "https://Checkout.Example.Test", "https://localhost", "",
        ).forEach { base -> withClue(base) { shouldThrow<IllegalArgumentException> { settings(base = base) } } }
    }

    test("TTLs below one second or with sub-millisecond parts, and bad versions, refuse start-up") {
        listOf(Duration.ZERO, Duration.ofMillis(999), Duration.ofSeconds(1).plusNanos(1)).forEach { d ->
            withClue(d) { shouldThrow<IllegalArgumentException> { settings(ttl = d) } }
        }
        listOf("", " ", "v".repeat(65)).forEach { v -> withClue(v) { shouldThrow<IllegalArgumentException> { settings(version = v) } } }
    }
})

package com.paycore.payment.payment.adapter.integration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * 결제 유효기간 설정 기동 검사(D-AUTHN-005, n-g, 검토 #2 n-3). 저장 컬럼은 DATETIME(3)이라 ms 미만 기간은 반올림으로 `checkout_expires_at = created_at`(DB CHECK 위반)이
 * 될 수 있다 → 기간은 ms 단위 정수이고 1초 이상이어야 한다. 위반은 설정 바인딩 실패 = 기동 실패다.
 */
class CheckoutValiditySettingsSpec : FunSpec({

    test("whole-millisecond durations of at least one second are accepted (Demo PT30M)") {
        CheckoutValiditySettings("checkout-validity-demo-1", Duration.ofMinutes(30)).duration shouldBe Duration.ofMinutes(30)
        CheckoutValiditySettings("v", Duration.ofSeconds(1)).duration shouldBe Duration.ofSeconds(1)
        CheckoutValiditySettings("v", Duration.ofMillis(1_001)).duration shouldBe Duration.ofMillis(1_001)
    }

    test("zero, negative, sub-second and sub-millisecond durations refuse start-up") {
        listOf(
            Duration.ZERO, Duration.ofSeconds(-1), Duration.ofMillis(999), Duration.ofNanos(1), Duration.ofSeconds(1).plusNanos(500_000),
        ).forEach { d -> withClue(d) { shouldThrow<IllegalArgumentException> { CheckoutValiditySettings("v", d) } } }
    }

    test("the version must be 1..64 printable ASCII characters") {
        listOf("", " ", "a".repeat(65), "버전").forEach { v -> withClue(v) { shouldThrow<IllegalArgumentException> { CheckoutValiditySettings(v, Duration.ofMinutes(30)) } } }
    }
})

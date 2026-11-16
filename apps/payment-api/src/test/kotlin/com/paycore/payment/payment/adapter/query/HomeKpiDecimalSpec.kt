package com.paycore.payment.payment.adapter.query

import com.paycore.payment.payment.domain.HomeKpiFigures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.BigInteger

/**
 * T-K1-14(b) 단위(ADMIN-HOME-KPI-001 rev 0.3 MINOR-6): SQL SUM 결과(DECIMAL → `BigDecimal`)를 `toBigIntegerExact()`로 정수로, net을 10진 문자열로 — Long 범위를 넘어도 정확하고,
 * 소수부가 있으면 거부한다(float·Long 경로 없음). 실제 금액을 Long 근처까지 쌓는 한도 우회 fixture는 쓰지 않는다.
 */
class HomeKpiDecimalSpec : BehaviorSpec({

    Given("SUM values as MySQL returns them (DECIMAL)") {
        Then("beyond Long the integer and its decimal string are exact, and a negative net keeps its sign") {
            val threeMax = BigDecimal(Long.MAX_VALUE.toString()).multiply(BigDecimal(3))
            HomeKpiDecimal.exact(threeMax).toString() shouldBe "27670116110564327421"
            val f = HomeKpiFigures(HomeKpiDecimal.exact(BigDecimal("5000")), HomeKpiDecimal.exact(threeMax), 1)
            f.netApprovedMinor.toString() shouldBe "-27670116110564322421"
            HomeKpiDecimal.exact(BigDecimal("12345678901234567890123456789012345678901")) shouldBe BigInteger("12345678901234567890123456789012345678901")
        }
        Then("a DECIMAL with scale but no fraction is accepted; a fraction is refused") {
            HomeKpiDecimal.exact(BigDecimal("150000.000")) shouldBe BigInteger.valueOf(150_000)
            shouldThrow<ArithmeticException> { HomeKpiDecimal.exact(BigDecimal("1.5")) }
        }
    }
})

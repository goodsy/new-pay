package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.MoneyFixture.KRW
import com.paycore.payment.payment.domain.MoneyFixture.USD
import com.paycore.payment.payment.domain.MoneyFixture.krwUnit
import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.usdUnit
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Money 계약(CP-CONTRACT-001 §4): D-CP-002(ISO 4217 통화·통화별 minor unit 일반 처리, 허용 통화는 기준정보, 초기 KRW·USD 검증)와
 * D-CPC-005(금액은 minor unit 정수, 1 이상·BIGINT(Long) 이하만 계약 강제, 업무 한도는 기준정보 정책).
 */
class MoneySpec : BehaviorSpec({

    Given("an ISO 4217 alphabetic currency code") {
        Then("three upper-case ASCII letters are accepted as given") {
            CurrencyCode("KRW").value shouldBe "KRW"
            CurrencyCode("USD").value shouldBe "USD"
        }
        Then("the code is not tied to a hard-coded allow list (enabling a currency is MID/contract reference data)") {
            CurrencyCode("JPY").value shouldBe "JPY"
        }
        Then("anything else is rejected without normalization and without echoing the input") {
            listOf("", "KR", "KRWW", "krw", "Krw", "K1W", " KRW", "KRW ", "KR W", "ＫＲＷ", "원원원").forEach { bad ->
                val error = shouldThrow<IllegalArgumentException> { CurrencyCode(bad) }
                if (bad.isNotBlank()) error.message!! shouldNotContain bad
            }
        }
    }

    Given("a currency unit from reference data (code + minor unit)") {
        Then("KRW has minor unit 0 and USD has minor unit 2 in the test fixture") {
            krwUnit.minorUnit shouldBe 0
            usdUnit.minorUnit shouldBe 2
        }
        Then("a minor unit outside ISO 4217's 0..4 is rejected") {
            shouldThrow<IllegalArgumentException> { CurrencyUnit(KRW, minorUnit = -1) }
            shouldThrow<IllegalArgumentException> { CurrencyUnit(USD, minorUnit = 5) }
            CurrencyUnit(CurrencyCode("CLF"), minorUnit = 4).minorUnit shouldBe 4
        }
    }

    Given("a requested amount in minor units (D-CPC-005)") {
        Then("1 is the smallest accepted amount") {
            Money(1, KRW).amountMinor shouldBe 1L
        }
        Then("0 and negative amounts are rejected") {
            shouldThrow<IllegalArgumentException> { Money(0, KRW) }
            shouldThrow<IllegalArgumentException> { Money(-1, KRW) }
            shouldThrow<IllegalArgumentException> { Money(Long.MIN_VALUE, USD) }
        }
        Then("BIGINT max (Long.MAX_VALUE) is accepted: no operational upper limit in code") {
            Money(Long.MAX_VALUE, KRW).amountMinor shouldBe Long.MAX_VALUE
        }
    }

    Given("two money values") {
        Then("they are equal only when amount and currency are both equal") {
            10_000L.won() shouldBe Money(10_000, CurrencyCode("KRW"))
            10_000L.won() shouldNotBe 10_001L.won()
            10_000L.won() shouldNotBe 10_000L.usdCents()
        }
    }

    Given("a minor-unit amount shown in major units with the reference-data minor unit (display only)") {
        Then("KRW (0 digits) keeps the integer") {
            10_000L.won().toMajorUnitString(krwUnit) shouldBe "10000"
        }
        Then("USD (2 digits) places the decimal point exactly") {
            1_050L.usdCents().toMajorUnitString(usdUnit) shouldBe "10.50"
            1L.usdCents().toMajorUnitString(usdUnit) shouldBe "0.01"
            Long.MAX_VALUE.usdCents().toMajorUnitString(usdUnit) shouldBe "92233720368547758.07"
        }
        Then("a unit of another currency is rejected") {
            shouldThrow<IllegalArgumentException> { 10_000L.won().toMajorUnitString(usdUnit) }
        }
    }

    Given("the money types") {
        Then("no field uses floating point") {
            listOf(Money::class.java, CurrencyUnit::class.java, CurrencyCode::class.java).forEach { type ->
                type.declaredFields.map { it.type }.forEach { fieldType ->
                    fieldType shouldNotBe Double::class.javaPrimitiveType
                    fieldType shouldNotBe Float::class.javaPrimitiveType
                    fieldType shouldNotBe Double::class.javaObjectType
                    fieldType shouldNotBe Float::class.javaObjectType
                }
            }
        }
    }
})

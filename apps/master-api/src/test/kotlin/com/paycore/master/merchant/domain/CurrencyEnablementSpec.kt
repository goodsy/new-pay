package com.paycore.master.merchant.domain

import com.paycore.master.merchant.domain.EntitlementFixture.evidence
import com.paycore.master.merchant.domain.EntitlementFixture.krw
import com.paycore.master.merchant.domain.EntitlementFixture.operator
import com.paycore.master.merchant.domain.EntitlementFixture.t0
import com.paycore.master.merchant.domain.EntitlementFixture.usd
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 통화 활성화 기록(D-MD-018). 기간 규칙은 D-MD-005 유추 해석(Coordinator 채택): 소급 시작·소급 종료 금지, 미래 종료 예약 허용, 같은 통화 중첩 금지.
 */
class CurrencyEnablementSpec : BehaviorSpec({

    fun rejection(block: () -> Unit): MasterRejection = shouldThrow<MasterRuleViolation>(block).rejection

    val issued = MasterFixture.issued()
    val account = issued.issuance.account

    fun enable(unit: CurrencyUnit, period: EffectivePeriod, existing: List<CurrencyEnablement> = emptyList(), at: Instant = t0) =
        CurrencyEnablement.enable(EntitlementFixture.enablementId(), account, unit, period, existing, evidence, at, operator)

    Given("a MID without currency enablements") {
        Then("KRW from now (open) and USD with a scheduled end are recorded; a start before the command time is refused") {
            enable(krw, EffectivePeriod(t0)).period shouldBe EffectivePeriod(t0)
            enable(usd, EffectivePeriod(t0.plusSeconds(60), t0.plusSeconds(3600))).period.to shouldBe t0.plusSeconds(3600)
            rejection { enable(krw, EffectivePeriod(t0.minusMillis(1))) } shouldBe MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE
        }

        Then("an overlapping period of the same currency is refused with the existing record as reference; another currency is independent") {
            val open = enable(krw, EffectivePeriod(t0))
            shouldThrow<MasterRuleViolation> { enable(krw, EffectivePeriod(t0.plusSeconds(10)), listOf(open)) }
                .let { it.rejection to it.reference } shouldBe (MasterRejection.CURRENCY_ENABLEMENT_OVERLAP to open.id.value)
            enable(usd, EffectivePeriod(t0), listOf(open)).unit shouldBe usd
        }
    }

    Given("an open KRW enablement") {
        Then("ending it in the future is allowed once; ending in the past or ending twice is refused") {
            val open = enable(krw, EffectivePeriod(t0))
            rejection { open.end(t0.minusMillis(1), t0, operator) } shouldBe MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE
            open.end(t0.plusSeconds(600), t0, operator)
            open.period shouldBe EffectivePeriod(t0, t0.plusSeconds(600))
            open.endedBy shouldBe operator
            rejection { open.end(t0.plusSeconds(900), t0, operator) } shouldBe MasterRejection.CURRENCY_ENABLEMENT_ALREADY_ENDED
        }

        Then("after its scheduled end a new KRW period may start at or after that end (no overlap)") {
            val first = enable(krw, EffectivePeriod(t0))
            first.end(t0.plusSeconds(600), t0, operator)
            enable(krw, EffectivePeriod(t0.plusSeconds(600)), listOf(first)).period.from shouldBe t0.plusSeconds(600)
        }

        Then("ending at the start instant would empty the period and is refused") {
            val open = enable(krw, EffectivePeriod(t0))
            rejection { open.end(t0, t0, operator) } shouldBe MasterRejection.EFFECTIVE_PERIOD_EMPTY
        }
    }

    Given("currency units") {
        Then("minor unit is limited to ISO 4217 range 0..4") {
            shouldThrow<IllegalArgumentException> { CurrencyUnit(CurrencyCode("KRW"), 5) }
            shouldThrow<IllegalArgumentException> { CurrencyCode("krw") }
        }
    }
})


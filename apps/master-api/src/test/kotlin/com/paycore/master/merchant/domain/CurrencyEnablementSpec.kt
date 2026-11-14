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

    Given("MERCHANT-OPS-002 §8.4 B3-2-1 (D-MOPS-009 ②): effective start boundaries — no new rule, the existing D-MD-018 `period.from < at` comparison") {
        // 명시 시각(KST 자정 경계 — ContractSpec 표와 같은 방식): at = 2026-11-13 09:00 KST, 오늘 00:00 KST = 11-12T15:00Z, 내일 00:00 KST = 11-13T15:00Z.
        val at = Instant.parse("2026-11-13T00:00:00.000Z")
        val todayKst = Instant.parse("2026-11-12T15:00:00.000Z")
        val tomorrowKst = Instant.parse("2026-11-13T15:00:00.000Z")

        Then("AT_EXECUTION: from = at (the value the Application resolves from lockedNow) is allowed and starts exactly at the command time") {
            val e = CurrencyEnablement.enable(EntitlementFixture.enablementId(), account, krw, EffectivePeriod(at), emptyList(), evidence, at, operator)
            e.period.from shouldBe at
            e.createdAt shouldBe at
        }

        Then("DATE: tomorrow 00:00 KST is allowed (a future start)") {
            CurrencyEnablement.enable(EntitlementFixture.enablementId(), account, krw, EffectivePeriod(tomorrowKst), emptyList(), evidence, at, operator)
                .period.from shouldBe tomorrowKst
        }

        Then("DATE: today 00:00 KST at 09:00 KST is a retroactive start (CURRENCY_ENABLEMENT_RETROACTIVE)") {
            rejection {
                CurrencyEnablement.enable(EntitlementFixture.enablementId(), account, krw, EffectivePeriod(todayKst), emptyList(), evidence, at, operator)
            } shouldBe MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE
        }

        Then("DATE: midnight passed while the approval waited — at = tomorrow 00:00:00.001 KST, from = tomorrow 00:00 KST → CURRENCY_ENABLEMENT_RETROACTIVE") {
            rejection {
                CurrencyEnablement.enable(
                    EntitlementFixture.enablementId(), account, krw, EffectivePeriod(tomorrowKst), emptyList(), evidence, tomorrowKst.plusMillis(1), operator,
                )
            } shouldBe MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE
        }
    }

    Given("currency units") {
        Then("minor unit is limited to ISO 4217 range 0..4") {
            shouldThrow<IllegalArgumentException> { CurrencyUnit(CurrencyCode("KRW"), 5) }
            shouldThrow<IllegalArgumentException> { CurrencyCode("krw") }
        }
    }
})


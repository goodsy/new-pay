package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.planPolicy
import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * PaymentPlan·CardType 규칙(D-AUTH-001, 명세 §5.4). 기대값은 명세 표에서 직접 적는다(Demo 정책: 2·3·6·12개월, 최소 50,000원).
 */
class PaymentPlanSpec : BehaviorSpec({

    Given("the installment plan value") {
        Then("months must be within the technical range 2..99 (operational months come from the versioned policy)") {
            PaymentPlan.Installment(2).months shouldBe 2
            PaymentPlan.Installment(99).months shouldBe 99
            shouldThrow<IllegalArgumentException> { PaymentPlan.Installment(1) }
            shouldThrow<IllegalArgumentException> { PaymentPlan.Installment(0) }
            shouldThrow<IllegalArgumentException> { PaymentPlan.Installment(100) }
        }
    }

    Given("the Demo plan policy and the plan rule at authorization start") {
        Then("lump sum is allowed for credit and check cards") {
            PaymentPlanRule.check(PaymentPlan.LumpSum, CardType.CREDIT, 10_000L.won(), planPolicy) shouldBe null
            PaymentPlanRule.check(PaymentPlan.LumpSum, CardType.CHECK, 10_000L.won(), planPolicy) shouldBe null
        }
        Then("a check card installment is rejected at the PG stage regardless of months or amount") {
            PaymentPlanRule.check(PaymentPlan.Installment(3), CardType.CHECK, 100_000L.won(), planPolicy) shouldBe PlanRejection.CHECK_CARD_INSTALLMENT
        }
        Then("a credit installment needs policy months and the minimum amount (boundary 50,000 allowed, 49,999 rejected)") {
            PaymentPlanRule.check(PaymentPlan.Installment(3), CardType.CREDIT, 50_000L.won(), planPolicy) shouldBe null
            PaymentPlanRule.check(PaymentPlan.Installment(3), CardType.CREDIT, 49_999L.won(), planPolicy) shouldBe
                PlanRejection.BELOW_MINIMUM_INSTALLMENT_AMOUNT
            PaymentPlanRule.check(PaymentPlan.Installment(5), CardType.CREDIT, 100_000L.won(), planPolicy) shouldBe
                PlanRejection.INSTALLMENT_MONTHS_NOT_ALLOWED
        }
        Then("a policy without installment months allows no installment") {
            val lumpSumOnly = PaymentPlanPolicy("demo-plan-lumpsum", MoneyFixture.KRW, emptySet(), null)
            PaymentPlanRule.check(PaymentPlan.Installment(2), CardType.CREDIT, 100_000L.won(), lumpSumOnly) shouldBe
                PlanRejection.INSTALLMENT_MONTHS_NOT_ALLOWED
        }
        Then("a policy for another currency is a caller defect") {
            shouldThrow<IllegalArgumentException> { PaymentPlanRule.check(PaymentPlan.LumpSum, CardType.CREDIT, 1_000L.usdCents(), planPolicy) }
            shouldThrow<IllegalArgumentException> { PaymentPlanPolicy("p", MoneyFixture.KRW, setOf(2), 100L.usdCents()) }
        }
    }
})

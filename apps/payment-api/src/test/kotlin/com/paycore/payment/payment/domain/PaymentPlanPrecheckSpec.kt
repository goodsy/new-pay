package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.MoneyFixture.KRW
import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 결제창 Plan 사전 판정(AUTHN-001 명세 §6.2·§7.1, AC 6 "허용 판정은 서버"): cardType을 모르는 시점의 개월·최소금액 판정. 체크카드 할부 거절은
 * cardType을 아는 승인 시작(AUTH-001)과 기관의 책임이라 여기서는 판정하지 않는다(AC1 §5.4). 정책 값은 Demo(운영값 아님).
 */
class PaymentPlanPrecheckSpec : BehaviorSpec({

    val policy = PaymentPlanPolicy("plan-demo-1", KRW, setOf(2, 3, 6), 50_000L.won())

    Given("the window's plan choice before the card type is known") {
        Then("lump sum is always pre-allowed") {
            PaymentPlanRule.precheck(PaymentPlan.LumpSum, 1L.won(), policy) shouldBe null
        }
        Then("an installment needs a policy month and at least the minimum amount (boundary inclusive)") {
            PaymentPlanRule.precheck(PaymentPlan.Installment(3), 50_000L.won(), policy) shouldBe null
            PaymentPlanRule.precheck(PaymentPlan.Installment(3), 49_999L.won(), policy) shouldBe PlanRejection.BELOW_MINIMUM_INSTALLMENT_AMOUNT
            PaymentPlanRule.precheck(PaymentPlan.Installment(12), 50_000L.won(), policy) shouldBe PlanRejection.INSTALLMENT_MONTHS_NOT_ALLOWED
        }
        Then("a policy without installment months allows only lump sum") {
            val lumpOnly = policy.copy(installmentMonths = emptySet())
            PaymentPlanRule.precheck(PaymentPlan.Installment(2), 1_000_000L.won(), lumpOnly) shouldBe PlanRejection.INSTALLMENT_MONTHS_NOT_ALLOWED
        }
        Then("a currency other than the policy currency is a caller defect") {
            shouldThrow<IllegalArgumentException> { PaymentPlanRule.precheck(PaymentPlan.LumpSum, 100L.usdCents(), policy) }
        }
    }

    Given("the authorization-start check (existing rule)") {
        Then("it agrees with the precheck for credit cards and adds only the check-card installment rejection") {
            listOf(PaymentPlan.LumpSum, PaymentPlan.Installment(3), PaymentPlan.Installment(12)).forEach { plan ->
                listOf(49_999L, 50_000L).forEach { amount ->
                    PaymentPlanRule.check(plan, CardType.CREDIT, amount.won(), policy) shouldBe PaymentPlanRule.precheck(plan, amount.won(), policy)
                }
            }
            PaymentPlanRule.check(PaymentPlan.Installment(3), CardType.CHECK, 50_000L.won(), policy) shouldBe PlanRejection.CHECK_CARD_INSTALLMENT
        }
    }
})

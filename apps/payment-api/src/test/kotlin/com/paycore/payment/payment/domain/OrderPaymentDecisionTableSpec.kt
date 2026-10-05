package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.OrderFixture.differentAmountIntent
import com.paycore.payment.payment.domain.OrderFixture.originalIntent
import com.paycore.payment.payment.domain.OrderFixture.paymentId
import com.paycore.payment.payment.domain.PaymentSlotDecision.Blocked
import com.paycore.payment.payment.domain.PaymentSlotDecision.CreateNewPayment
import com.paycore.payment.payment.domain.PaymentSlotDecision.FixedIntentConflict
import com.paycore.payment.payment.domain.PaymentSlotDecision.UseExistingPayment
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 주문 재사용 판정 표 — 구현 계약 §4 표 + 확정 문서 §2(상태별 새 시도) + D-CPD-002. 현재 Payment 상태 × 종료 근거 × 고정 입력
 * 동일성 × incident hold를 빠짐없이 나열한다. 기대값은 시험 안에 명시한다(운영 판정 함수로 계산하지 않음).
 *
 * 같은 멱등키 재요청(과거 결과 반환)은 application/idempotency 범위(CP-API)라 이 표에 없다. 이 표의 "새 Payment"는 새 요청키의
 * 새 고객 시도를 뜻한다.
 */
class OrderPaymentDecisionTableSpec : BehaviorSpec({

    val first = paymentId(1)

    Given("no order payment control exists for the order key") {
        Then("a first payment is created and occupies generation 1") {
            OrderPaymentControl.decideForNewOrder() shouldBe CreateNewPayment(nextSlotGeneration = 1)
        }
    }

    // 활성(REQUESTED 포함) 또는 결과 확인 중 시도: 새 Payment 없음, 기존 참조.
    for (status in listOf(REQUESTED, AUTHORIZING, UNKNOWN)) {
        Given("the current payment is $status") {
            When("the same fixed order input is requested again (another request key)") {
                Then("the existing payment is reused and no new payment is created") {
                    val s = OrderPaymentControlFixture.withCurrentPayment(status)
                    s.control.decide(originalIntent, s.current) shouldBe UseExistingPayment(first, status)
                }
            }
            When("a different fixed order input is requested") {
                Then("it conflicts") {
                    val s = OrderPaymentControlFixture.withCurrentPayment(status)
                    s.control.decide(differentAmountIntent, s.current) shouldBe FixedIntentConflict(first)
                }
            }
        }
    }

    Given("the current payment is AUTHORIZED") {
        When("the same fixed order input is requested again") {
            Then("no new payment: blocked with the authorized payment as reference (new order ID required)") {
                val s = OrderPaymentControlFixture.authorizedOrder()
                s.control.decide(originalIntent, s.current) shouldBe Blocked(BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED, first)
            }
        }
        When("a different fixed order input is requested") {
            Then("it conflicts") {
                val s = OrderPaymentControlFixture.authorizedOrder()
                s.control.decide(differentAmountIntent, s.current) shouldBe FixedIntentConflict(first)
            }
        }
    }

    for (status in listOf(FAILED, EXPIRED)) {
        for (evidence in TerminationEvidenceOracle.evidenceOrNone) {
            val label = TerminationEvidenceOracle.label(evidence)
            val safe = TerminationEvidenceOracle.isSufficient(status, evidence)
            val cross = !safe && TerminationEvidenceOracle.isSufficientForAnotherStatus(status, evidence)
            Given("the current payment is $status with $label") {
                When("the same fixed order input is requested with a new request key") {
                    val expected = if (safe) {
                        CreateNewPayment(nextSlotGeneration = 2)
                    } else {
                        Blocked(BlockReason.TERMINATION_EVIDENCE_INSUFFICIENT, first)
                    }
                    val outcome = when {
                        safe -> "a new payment is allowed at generation 2"
                        cross -> "a new payment is blocked: cross evidence is not accepted (D-CPD-003)"
                        else -> "a new payment is blocked until evidence is confirmed"
                    }
                    Then(outcome) {
                        val s = OrderPaymentControlFixture.withCurrentPayment(status, evidence)
                        s.control.decide(originalIntent, s.current) shouldBe expected
                    }
                }
                When("a different fixed order input is requested") {
                    Then("it conflicts (a changed order needs a new order ID)") {
                        val s = OrderPaymentControlFixture.withCurrentPayment(status, evidence)
                        s.control.decide(differentAmountIntent, s.current) shouldBe FixedIntentConflict(first)
                    }
                }
                When("a success is reported for it after termination (incident hold)") {
                    Then("a new payment is blocked by the incident hold with the existing payment as reference (D-CPD-004)") {
                        val s = OrderPaymentControlFixture.withCurrentPayment(status, evidence)
                        s.control.reportSuccessAfterTermination(s.current)
                        s.control.decide(originalIntent, s.current) shouldBe Blocked(BlockReason.INCIDENT_HOLD, first)
                    }
                }
            }
        }
    }
})

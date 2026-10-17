package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.OrderFixture.demoOrder
import com.paycore.payment.payment.domain.OrderFixture.differentAmountIntent
import com.paycore.payment.payment.domain.OrderFixture.originalIntent
import com.paycore.payment.payment.domain.OrderFixture.paymentId
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
import com.paycore.payment.payment.domain.TerminationEvidence.TIMEOUT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 영속 복원 factory(CP-DB-001). persistence Mapper가 저장된 상태를 domain으로 되돌릴 때만 쓰는 공식 경로다. 시험 편의 API가 아니며
 * Named Test Fixture는 여전히 `Payment.request`·전이 메서드로 상태를 만든다(Test Fixture Policy). 여기서는 복원이 domain 불변식을
 * 다시 검사하고(존재할 수 없는 저장 상태는 거부), 복원된 객체가 공식 경로로 만든 객체와 같은 규칙으로 동작하는지 본다.
 */
class ReconstitutionSpec : BehaviorSpec({

    Given("a stored payment row") {
        When("it is restored with its status and termination evidence") {
            Then("the state is kept and later transitions follow the normal rules") {
                val restored = Payment.reconstitute(paymentId(1), demoOrder, originalIntent, 3, REQUESTED, emptyList(), PaymentFixture.demoWindow)
                restored.status shouldBe REQUESTED
                restored.slotGeneration shouldBe 3
                restored.startAuthorization()
                restored.status shouldBe AUTHORIZING
            }

            Then("a restored FAILED payment keeps its evidence and its safe-termination judgement") {
                val restored = Payment.reconstitute(paymentId(1), demoOrder, originalIntent, 1, FAILED, listOf(TIMEOUT, INSTITUTION_CONFIRMED_DECLINE), null)
                restored.terminationEvidence shouldBe listOf(TIMEOUT, INSTITUTION_CONFIRMED_DECLINE)
                restored.hasSafeTermination() shouldBe true
                shouldThrow<InvalidPaymentTransitionException> { restored.startAuthorization() }
            }
        }

        When("the stored state cannot exist in the domain") {
            Then("evidence on a non-terminated payment is rejected") {
                listOf(REQUESTED, AUTHORIZING, UNKNOWN, AUTHORIZED).forEach { status ->
                    shouldThrow<IllegalArgumentException> {
                        Payment.reconstitute(paymentId(1), demoOrder, originalIntent, 1, status, listOf(INSTITUTION_CONFIRMED_DECLINE), null)
                    }
                }
            }

            Then("a non-positive slot generation is rejected") {
                shouldThrow<IllegalArgumentException> { Payment.reconstitute(paymentId(1), demoOrder, originalIntent, 0, EXPIRED, emptyList(), null) }
            }
        }
    }

    Given("a stored order payment control row") {
        val current = PaymentFixture.failedWithConfirmedDecline(n = 1, slotGeneration = 2)

        When("it is restored at generation 2 with its current payment") {
            val control = OrderPaymentControl.reconstitute(demoOrder, originalIntent, paymentId(1), 2, null, emptyList())

            Then("decisions use the restored generation and fixed intent") {
                control.decide(originalIntent, current) shouldBe PaymentSlotDecision.CreateNewPayment(3)
                control.decide(differentAmountIntent, current).shouldBeInstanceOf<PaymentSlotDecision.FixedIntentConflict>()
                shouldThrow<StaleSlotException> { control.decide(originalIntent, PaymentFixture.failedWithConfirmedDecline(1, 1)) }
            }
        }

        When("it is restored with an incident hold and a past authorization") {
            val hold = IncidentHold(paymentId(9), IncidentReason.SUCCESS_AFTER_TERMINATION)
            val control = OrderPaymentControl.reconstitute(demoOrder, originalIntent, paymentId(1), 2, paymentId(5), listOf(hold))

            Then("the hold and the authorization history are kept") {
                control.incidentHolds shouldBe listOf(hold)
                control.authorizedPaymentId shouldBe paymentId(5)
                control.decide(originalIntent, current) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, paymentId(1))
            }
        }

        When("the stored state cannot exist in the domain") {
            Then("a non-positive generation or duplicated holds are rejected") {
                shouldThrow<IllegalArgumentException> {
                    OrderPaymentControl.reconstitute(demoOrder, originalIntent, paymentId(1), 0, null, emptyList())
                }
                val hold = IncidentHold(paymentId(9), IncidentReason.SUCCESS_AFTER_TERMINATION)
                shouldThrow<IllegalArgumentException> {
                    OrderPaymentControl.reconstitute(demoOrder, originalIntent, paymentId(1), 1, null, listOf(hold, hold))
                }
            }
        }
    }

    Given("a payment key (external key that points at a payment, CP-CONTRACT-001 §5)") {
        Then("an empty, overlong or ill-formed value is rejected without echoing it") {
            PaymentKey("pk_" + "a".repeat(61)).value.length shouldBe 64
            listOf("", "a".repeat(65), "pk\uD800").forEach { bad ->
                val error = shouldThrow<IllegalArgumentException> { PaymentKey(bad) }
                if (bad.isNotEmpty()) error.message.orEmpty() shouldNotContain bad
            }
        }
    }

    Given("payment method (initially domestic credit card only)") {
        Then("CARD is the only method") {
            PaymentMethod.entries shouldBe listOf(PaymentMethod.CARD)
        }
    }
})

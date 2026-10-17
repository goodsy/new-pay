package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.OrderFixture.differentAmountIntent
import com.paycore.payment.payment.domain.OrderFixture.differentCurrencyIntent
import com.paycore.payment.payment.domain.OrderFixture.differentSiteIntent
import com.paycore.payment.payment.domain.OrderFixture.newPurchaseOrder
import com.paycore.payment.payment.domain.OrderFixture.originalIntent
import com.paycore.payment.payment.domain.OrderFixture.paymentId
import com.paycore.payment.payment.domain.PaymentSlotDecision.Blocked
import com.paycore.payment.payment.domain.PaymentSlotDecision.FixedIntentConflict
import com.paycore.payment.payment.domain.PaymentSlotDecision.UseExistingPayment
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN
import com.paycore.payment.payment.domain.TerminationEvidence.INQUIRY_NOT_FOUND
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * OrderPaymentControl 시나리오 — 구현 계약 §7 CP-05·CP-06·CP-09의 domain 부분과 확정 문서 §6의 5·6·7·8·9.
 *
 * 이 시험은 단일 스레드 domain 규칙 증거다. 실제 DB 경쟁(동시 생성 수렴·UNIQUE·잠금·rollback·응답 유실, CP-03/04/08/10)은
 * CP-DB/CP-API에서 실제 MySQL 다중 connection으로 검증해야 하며 이 시험을 그 증거로 쓰지 않는다.
 */
class OrderPaymentControlSpec : BehaviorSpec({

    Given("an order opened with its first REQUESTED payment") {
        Then("the control holds the order key, the fixed input, the first payment and generation 1") {
            val s = OrderPaymentControlFixture.openedWithRequestedPayment()
            s.control.orderKey shouldBe OrderFixture.demoOrder
            s.control.fixedIntent shouldBe originalIntent
            s.control.currentPaymentId shouldBe paymentId(1)
            s.control.generation shouldBe 1L
            s.control.authorizedPaymentId shouldBe null
            s.control.incidentHolds shouldBe emptyList()
            s.control.isOnIncidentHold() shouldBe false
        }
        When("another create request with the same content arrives with a different request key (§6-1, domain part)") {
            Then("it converges on the existing active payment; a second occupation is rejected (one active incl. REQUESTED)") {
                val s = OrderPaymentControlFixture.openedWithRequestedPayment()
                s.control.decide(originalIntent, s.current) shouldBe UseExistingPayment(paymentId(1), REQUESTED)
                val error = shouldThrow<SlotOccupationRejectedException> {
                    s.control.occupy(PaymentFixture.requested(n = 2, slotGeneration = 2), s.current)
                }
                error.decision shouldBe UseExistingPayment(paymentId(1), REQUESTED)
                s.control.currentPaymentId shouldBe paymentId(1)
                s.control.generation shouldBe 1L
            }
        }
    }

    Given("opening a control with a payment that is not a fresh first attempt (invalid input, explicit)") {
        Then("a non-REQUESTED payment is rejected") {
            shouldThrow<IllegalArgumentException> { OrderPaymentControl.open(PaymentFixture.authorizing()) }
        }
        Then("a payment that is not at generation 1 is rejected") {
            shouldThrow<IllegalArgumentException> { OrderPaymentControl.open(PaymentFixture.requested(slotGeneration = 2)) }
        }
    }

    Given("UNKNOWN authorization (§6-5)") {
        When("the customer tries a new card / new request key on the same order") {
            Then("no new payment: the existing UNKNOWN payment is referenced and occupation is rejected") {
                val s = OrderPaymentControlFixture.withCurrentPayment(UNKNOWN)
                s.control.decide(originalIntent, s.current) shouldBe UseExistingPayment(paymentId(1), UNKNOWN)
                shouldThrow<SlotOccupationRejectedException> {
                    s.control.occupy(PaymentFixture.requested(n = 2, slotGeneration = 2), s.current)
                }
                s.control.currentPaymentId shouldBe paymentId(1)
            }
        }
        When("only a timeout / inquiry NOT_FOUND is known") {
            Then("the payment cannot be closed by it, so the order stays blocked for confirmation") {
                val s = OrderPaymentControlFixture.withCurrentPayment(UNKNOWN)
                shouldThrow<InvalidPaymentTransitionException> { s.current.fail(INQUIRY_NOT_FOUND) }
                s.control.decide(originalIntent, s.current) shouldBe UseExistingPayment(paymentId(1), UNKNOWN)
            }
        }
    }

    Given("a confirmed decline (§6-6, CP-06)") {
        When("the customer retries with a new request key and the same fixed input") {
            Then("a new payment occupies generation 2, the declined payment history is preserved, and it can succeed") {
                val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
                val declined = s.history.first()
                s.control.currentPaymentId shouldBe paymentId(2)
                s.control.generation shouldBe 2L
                declined.status shouldBe FAILED
                declined.terminationEvidence shouldBe listOf(INSTITUTION_CONFIRMED_DECLINE)

                s.control.startAuthorization(s.current)
                s.current.markAuthorized()
                s.control.recordAuthorized(s.current)
                s.control.authorizedPaymentId shouldBe paymentId(2)
            }
        }
    }

    Given("an authorized order that is later fully cancelled/refunded (§6-7, CP-05)") {
        When("the same order is requested again") {
            Then("it stays blocked: the success history is kept and a new order ID is required") {
                val s = OrderPaymentControlFixture.authorizedOrder()
                // 취소/환불은 Payment 승인 상태·control 성공 이력을 바꾸지 않는다(반환 상태 FULL은 새 승인 허용 조건이 아님).
                s.control.decide(originalIntent, s.current) shouldBe Blocked(BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED, paymentId(1))
                shouldThrow<SlotOccupationRejectedException> {
                    s.control.occupy(PaymentFixture.requested(n = 2, slotGeneration = 2), s.current)
                }
                s.control.authorizedPaymentId shouldBe paymentId(1)
            }
        }
        When("the customer repurchases with a new merchant order ID") {
            Then("the new order key has no control, so a first payment can be created for it") {
                OrderPaymentControl.decideForNewOrder() shouldBe PaymentSlotDecision.CreateNewPayment(1)
                val repurchase = Payment.request(paymentId(9), newPurchaseOrder, originalIntent, 1, PaymentFixture.demoWindow)
                val control = OrderPaymentControl.open(repurchase)
                control.orderKey shouldBe newPurchaseOrder
                control.currentPaymentId shouldBe paymentId(9)
            }
        }
    }

    Given("the same order with a different amount, currency or Site (§6-8)") {
        val changed = listOf(differentAmountIntent, differentCurrencyIntent, differentSiteIntent)
        Then("it conflicts while the payment is active") {
            val s = OrderPaymentControlFixture.openedWithRequestedPayment()
            for (intent in changed) s.control.decide(intent, s.current) shouldBe FixedIntentConflict(paymentId(1))
        }
        Then("it conflicts even after a safe termination (a changed order needs a new order ID)") {
            val s = OrderPaymentControlFixture.withCurrentPayment(FAILED, INSTITUTION_CONFIRMED_DECLINE)
            for (intent in changed) s.control.decide(intent, s.current) shouldBe FixedIntentConflict(paymentId(1))
        }
        Then("a new payment with a different fixed input cannot occupy the slot") {
            val s = OrderPaymentControlFixture.withCurrentPayment(FAILED, INSTITUTION_CONFIRMED_DECLINE)
            val error = shouldThrow<SlotOccupationRejectedException> {
                s.control.occupy(PaymentFixture.requested(n = 2, slotGeneration = 2, intent = differentAmountIntent), s.current)
            }
            error.decision shouldBe FixedIntentConflict(paymentId(1))
            s.control.generation shouldBe 1L
        }
    }

    Given("a late success after a confirmed decline (§6-9, CP-09)") {
        When("the success is reported for the declined payment") {
            Then("the FAILED status is not overwritten and the order is put on incident hold") {
                val s = OrderPaymentControlFixture.withCurrentPayment(FAILED, INSTITUTION_CONFIRMED_DECLINE)
                shouldThrow<InvalidPaymentTransitionException> { s.current.markAuthorized() }
                s.control.reportSuccessAfterTermination(s.current)
                s.current.status shouldBe FAILED
                s.control.isOnIncidentHold() shouldBe true
                s.control.incidentHolds shouldBe listOf(IncidentHold(paymentId(1), IncidentReason.SUCCESS_AFTER_TERMINATION))
                shouldThrow<SlotOccupationRejectedException> {
                    s.control.occupy(PaymentFixture.requested(n = 2, slotGeneration = 2), s.current)
                }
            }
        }
        When("the same late success arrives three times (response, notification, inquiry)") {
            Then("the hold is recorded once per (payment, reason): duplicate reports are no-ops") {
                val s = OrderPaymentControlFixture.withCurrentPayment(FAILED, INSTITUTION_CONFIRMED_DECLINE)
                repeat(3) { s.control.reportSuccessAfterTermination(s.current) }
                s.control.incidentHolds shouldBe listOf(IncidentHold(paymentId(1), IncidentReason.SUCCESS_AFTER_TERMINATION))
                s.control.decide(originalIntent, s.current) shouldBe Blocked(BlockReason.INCIDENT_HOLD, paymentId(1))
            }
        }
        When("late successes are reported for two different terminated payments of the order") {
            Then("each payment gets its own hold (deduplication is per payment, not per order)") {
                val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
                s.current.startAuthorization()
                s.current.fail(INSTITUTION_CONFIRMED_DECLINE)
                s.control.reportSuccessAfterTermination(s.history.first())
                s.control.reportSuccessAfterTermination(s.current)
                s.control.reportSuccessAfterTermination(s.history.first())
                s.control.incidentHolds shouldBe listOf(
                    IncidentHold(paymentId(1), IncidentReason.SUCCESS_AFTER_TERMINATION),
                    IncidentHold(paymentId(2), IncidentReason.SUCCESS_AFTER_TERMINATION),
                )
            }
        }
        When("a success is reported for a payment that is not terminated (invalid input, explicit)") {
            Then("it is rejected: a success for an active attempt is a normal result, not an incident") {
                val s = OrderPaymentControlFixture.withCurrentPayment(UNKNOWN)
                shouldThrow<IllegalArgumentException> { s.control.reportSuccessAfterTermination(s.current) }
                s.control.isOnIncidentHold() shouldBe false
            }
        }
    }

    Given("a retried order where generation 2 now belongs to a new payment (CP-09 stale results)") {
        When("a decision made before the retry tries to occupy again") {
            Then("the stale generation is rejected and the new occupation is kept") {
                val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
                val declined = s.history.first()
                shouldThrow<StaleSlotException> {
                    s.control.occupy(PaymentFixture.requested(n = 3, slotGeneration = 2), declined)
                }
                s.control.currentPaymentId shouldBe paymentId(2)
                s.control.generation shouldBe 2L
            }
        }
        When("a new payment carries a generation other than current + 1 while a new payment is otherwise allowed") {
            Then("occupation is rejected as stale (skipped or repeated generation)") {
                val s = OrderPaymentControlFixture.withCurrentPayment(FAILED, INSTITUTION_CONFIRMED_DECLINE)
                for (wrongGeneration in listOf(1L, 3L)) {
                    shouldThrow<StaleSlotException> {
                        s.control.occupy(PaymentFixture.requested(n = 2, slotGeneration = wrongGeneration), s.current)
                    }
                }
                s.control.currentPaymentId shouldBe paymentId(1)
                s.control.generation shouldBe 1L
            }
        }
        When("a payment has the current payment ID but another generation (invalid input, explicit: IDs are never reused)") {
            Then("it is not accepted as the current payment") {
                val s = OrderPaymentControlFixture.openedWithRequestedPayment()
                val sameIdOtherGeneration = PaymentFixture.requested(n = 1, slotGeneration = 2)
                shouldThrow<StaleSlotException> { s.control.decide(originalIntent, sameIdOtherGeneration) }
                shouldThrow<StaleSlotException> { s.control.startAuthorization(sameIdOtherGeneration) }
                sameIdOtherGeneration.status shouldBe REQUESTED
            }
        }
        When("the old payment is used as if it were the current one") {
            Then("deciding, recording success and starting authorization on it are rejected") {
                val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
                val declined = s.history.first()
                shouldThrow<StaleSlotException> { s.control.decide(originalIntent, declined) }
                shouldThrow<StaleSlotException> { s.control.startAuthorization(declined) }
                shouldThrow<StaleSlotException> { s.control.recordAuthorized(declined) }
            }
        }
        When("an old expiry / failure result for the old payment arrives") {
            Then("it cannot change the old payment nor release the new occupation") {
                val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
                val declined = s.history.first()
                shouldThrow<InvalidPaymentTransitionException> { declined.expire(INSTITUTION_CONFIRMED_NO_EFFECT) }
                shouldThrow<InvalidPaymentTransitionException> { declined.fail(INSTITUTION_CONFIRMED_DECLINE) }
                s.control.currentPaymentId shouldBe paymentId(2)
                s.control.generation shouldBe 2L
                s.control.decide(originalIntent, s.current) shouldBe UseExistingPayment(paymentId(2), REQUESTED)
            }
        }
        When("a late success is reported for the old (declined) payment") {
            Then("the order is put on incident hold, the new payment cannot start authorization, and a same-input request is blocked with the existing payment as reference (D-CPD-004)") {
                val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
                val declined = s.history.first()
                s.control.reportSuccessAfterTermination(declined)
                s.control.incidentHolds shouldBe listOf(IncidentHold(paymentId(1), IncidentReason.SUCCESS_AFTER_TERMINATION))
                val error = shouldThrow<AuthorizationStartRejectedException> { s.control.startAuthorization(s.current) }
                error.reason shouldBe BlockReason.INCIDENT_HOLD
                // L-2: 검사 실패는 전이 전이다 — 현재 Payment는 REQUESTED 그대로다.
                s.current.status shouldBe REQUESTED
                s.control.decide(originalIntent, s.current) shouldBe Blocked(BlockReason.INCIDENT_HOLD, paymentId(2))
                s.control.currentPaymentId shouldBe paymentId(2)
            }
        }
    }

    Given("authorization start is serialized through the order slot") {
        Then("the current REQUESTED payment starts through the control (slot check and transition are one operation, L-2)") {
            val s = OrderPaymentControlFixture.openedWithRequestedPayment()
            s.control.startAuthorization(s.current)
            s.current.status shouldBe PaymentStatus.AUTHORIZING
        }
        Then("a payment that is no longer REQUESTED may not start again") {
            val s = OrderPaymentControlFixture.withCurrentPayment(PaymentStatus.AUTHORIZING)
            shouldThrow<InvalidPaymentTransitionException> { s.control.startAuthorization(s.current) }
            s.current.status shouldBe PaymentStatus.AUTHORIZING
        }
        Then("an expired window may not start (stale screen)") {
            val s = OrderPaymentControlFixture.withCurrentPayment(PaymentStatus.EXPIRED, TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
            shouldThrow<InvalidPaymentTransitionException> { s.control.startAuthorization(s.current) }
            s.current.status shouldBe PaymentStatus.EXPIRED
        }
    }

    Given("recording an authorization success on the control") {
        Then("only the current AUTHORIZED payment of the current generation is accepted") {
            val s = OrderPaymentControlFixture.withCurrentPayment(PaymentStatus.AUTHORIZING)
            shouldThrow<IllegalArgumentException> { s.control.recordAuthorized(s.current) }
            s.control.authorizedPaymentId shouldBe null
            s.current.markAuthorized()
            s.control.recordAuthorized(s.current)
            s.control.authorizedPaymentId shouldBe paymentId(1)
        }
        Then("a success that arrives while on incident hold is still recorded (facts are not dropped) and stays blocked") {
            val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
            s.current.startAuthorization()
            s.control.reportSuccessAfterTermination(s.history.first())
            s.current.markAuthorized()
            s.control.recordAuthorized(s.current)
            s.control.authorizedPaymentId shouldBe paymentId(2)
            s.control.decide(originalIntent, s.current).shouldBeInstanceOf<Blocked>().reason shouldBe BlockReason.INCIDENT_HOLD
        }
    }

    Given("a new payment that reuses a payment ID the order already knows (invalid input, explicit: IDs are never reused)") {
        Then("the current payment ID cannot occupy the next generation even when a new payment is otherwise allowed") {
            val s = OrderPaymentControlFixture.withCurrentPayment(FAILED, INSTITUTION_CONFIRMED_DECLINE)
            val reused = PaymentFixture.requested(n = 1, slotGeneration = 2)
            val error = shouldThrow<PaymentIdReuseException> { s.control.occupy(reused, s.current) }
            error.paymentId shouldBe paymentId(1)
            s.control.currentPaymentId shouldBe paymentId(1)
            s.control.generation shouldBe 1L
        }
        Then("a past payment ID known from an incident hold cannot occupy the slot") {
            val s = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
            s.control.reportSuccessAfterTermination(s.history.first())
            val reused = PaymentFixture.requested(n = 1, slotGeneration = 3)
            val error = shouldThrow<PaymentIdReuseException> { s.control.occupy(reused, s.current) }
            error.paymentId shouldBe paymentId(1)
            s.control.currentPaymentId shouldBe paymentId(2)
            s.control.generation shouldBe 2L
        }
    }

    Given("a payment for a different order key (invalid input, explicit)") {
        Then("it cannot occupy this order's slot") {
            val s = OrderPaymentControlFixture.withCurrentPayment(FAILED, INSTITUTION_CONFIRMED_DECLINE)
            val other = Payment.request(paymentId(2), newPurchaseOrder, originalIntent, 2, PaymentFixture.demoWindow)
            shouldThrow<IllegalArgumentException> { s.control.occupy(other, s.current) }
            s.control.currentPaymentId shouldBe paymentId(1)
        }
    }
})

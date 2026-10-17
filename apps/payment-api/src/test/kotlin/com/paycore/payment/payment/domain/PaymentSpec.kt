package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.OrderFixture.originalIntent
import com.paycore.payment.payment.domain.OrderFixture.paymentId
import com.paycore.payment.payment.domain.OrderFixture.demoOrder
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZED
import com.paycore.payment.payment.domain.PaymentStatus.AUTHORIZING
import com.paycore.payment.payment.domain.PaymentStatus.EXPIRED
import com.paycore.payment.payment.domain.PaymentStatus.FAILED
import com.paycore.payment.payment.domain.PaymentStatus.REQUESTED
import com.paycore.payment.payment.domain.PaymentStatus.UNKNOWN
import com.paycore.payment.payment.domain.TerminationEvidence.INQUIRY_NOT_FOUND
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
import com.paycore.payment.payment.domain.TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT
import com.paycore.payment.payment.domain.TerminationEvidence.TIMEOUT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Payment 상태와 확정 불변식(활성 규칙에 필요한 최소 전이만. 기관 승인 전이 상세는 AUTH 범위).
 * - 확정 문서 §3: 이미 전송했거나 전송 여부가 불명확한 시도(AUTHORIZING/UNKNOWN)는 시간 경과(timeout·lease 만료·근거 없음)나
 *   조회 NOT_FOUND만으로 EXPIRED/FAILED로 전환하지 않는다 — D-CPD-002 충분 근거가 있을 때만 종료한다.
 * - 오래된 결제창: 만료/종료된 Payment는 승인을 다시 시작할 수 없다(확정 문서 §3, §6-3의 domain 부분).
 * - 확정 거절/만료 뒤 늦은 성공은 종료 상태를 덮어쓰지 않는다(사건 처리는 OrderPaymentControl, §4).
 */
class PaymentSpec : BehaviorSpec({

    Given("a customer opens a payment window for an order") {
        When("the payment is requested through the domain factory") {
            val p = Payment.request(paymentId(1), demoOrder, originalIntent, 1, PaymentFixture.demoWindow)
            Then("it is REQUESTED with no termination evidence and no safe termination") {
                p.status shouldBe REQUESTED
                p.id shouldBe paymentId(1)
                p.orderKey shouldBe demoOrder
                p.intent shouldBe originalIntent
                p.slotGeneration shouldBe 1L
                p.terminationEvidence shouldBe emptyList()
                p.hasSafeTermination() shouldBe false
            }
        }
        When("the slot generation is not positive (invalid input, explicit)") {
            Then("the factory rejects it") {
                shouldThrow<IllegalArgumentException> { Payment.request(paymentId(1), demoOrder, originalIntent, 0, PaymentFixture.demoWindow) }
            }
        }
    }

    Given("a REQUESTED payment") {
        Then("authorization starts, may become UNKNOWN and then AUTHORIZED") {
            val p = PaymentFixture.requested()
            p.startAuthorization(); p.status shouldBe AUTHORIZING
            p.markUnknown(); p.status shouldBe UNKNOWN
            p.markAuthorized(); p.status shouldBe AUTHORIZED
        }
        Then("it cannot be marked UNKNOWN or AUTHORIZED without starting authorization") {
            shouldThrow<InvalidPaymentTransitionException> { PaymentFixture.requested().markUnknown() }
            shouldThrow<InvalidPaymentTransitionException> { PaymentFixture.requested().markAuthorized() }
        }
        Then("since nothing was transmitted it may expire or fail; the given evidence is recorded as is") {
            PaymentFixture.expiredWithoutEvidence().let {
                it.status shouldBe EXPIRED
                it.terminationEvidence shouldBe emptyList()
                it.hasSafeTermination() shouldBe false
            }
            PaymentFixture.failedWithOnlyTimeout().let {
                it.status shouldBe FAILED
                it.terminationEvidence shouldBe listOf(TIMEOUT)
                it.hasSafeTermination() shouldBe false
            }
        }
    }

    // 확정 문서 §3 + D-CPD-002: 전송했거나 불명확한 시도의 종료는 충분 근거가 있을 때만.
    for (from in listOf(AUTHORIZING, UNKNOWN)) {
        Given("a $from payment (transmitted or transmission uncertain)") {
            for (target in listOf(FAILED, EXPIRED)) {
                for (evidence in TerminationEvidenceOracle.evidenceOrNone) {
                    val allowed = TerminationEvidenceOracle.isSufficient(target, evidence)
                    val label = TerminationEvidenceOracle.label(evidence)
                    When("it is closed as $target with $label") {
                        Then(if (allowed) "it becomes $target with a safe termination" else "the transition is rejected and nothing changes") {
                            val p = PaymentFixture.inStatus(from)
                            val close = { if (target == FAILED) p.fail(evidence) else p.expire(evidence) }
                            if (allowed) {
                                close()
                                p.status shouldBe target
                                p.terminationEvidence shouldBe listOfNotNull(evidence)
                                p.hasSafeTermination() shouldBe true
                            } else {
                                shouldThrow<InvalidPaymentTransitionException> { close() }
                                p.status shouldBe from
                                p.terminationEvidence shouldBe emptyList()
                            }
                        }
                    }
                }
            }
            Then("authorization cannot start again") {
                val p = PaymentFixture.inStatus(from)
                shouldThrow<InvalidPaymentTransitionException> { p.startAuthorization() }
                p.status shouldBe from
            }
        }
    }

    Given("an expired payment window (stale screen)") {
        val p = PaymentFixture.expiredUntransmittedWithSessionDiscarded()
        Then("authorization cannot start") {
            shouldThrow<InvalidPaymentTransitionException> { p.startAuthorization() }
            p.status shouldBe EXPIRED
        }
    }

    val finals = mapOf(
        "failed with a confirmed decline" to { PaymentFixture.failedWithConfirmedDecline() },
        "expired with confirmed no effect" to { PaymentFixture.expiredWithConfirmedNoEffect() },
        "authorized" to { PaymentFixture.authorized() },
    )
    for ((name, make) in finals) {
        Given("a payment $name") {
            When("a late result (success, unknown, decline, expiry) arrives for it") {
                Then("every authorization transition is rejected and the status is not overwritten") {
                    val p = make()
                    val before = p.status
                    shouldThrow<InvalidPaymentTransitionException> { p.startAuthorization() }
                    shouldThrow<InvalidPaymentTransitionException> { p.markUnknown() }
                    shouldThrow<InvalidPaymentTransitionException> { p.markAuthorized() }
                    shouldThrow<InvalidPaymentTransitionException> { p.fail(INSTITUTION_CONFIRMED_DECLINE) }
                    shouldThrow<InvalidPaymentTransitionException> { p.expire(INSTITUTION_CONFIRMED_NO_EFFECT) }
                    p.status shouldBe before
                }
            }
        }
    }

    Given("an expired payment without evidence") {
        When("evidence is confirmed later") {
            Then("it is appended (append-only) and only a sufficient one makes the termination safe") {
                val p = PaymentFixture.expiredWithoutEvidence()
                p.addTerminationEvidence(INQUIRY_NOT_FOUND)
                p.hasSafeTermination() shouldBe false
                p.addTerminationEvidence(INSTITUTION_CONFIRMED_NO_EFFECT)
                p.hasSafeTermination() shouldBe true
                p.terminationEvidence shouldBe listOf(INQUIRY_NOT_FOUND, INSTITUTION_CONFIRMED_NO_EFFECT)
            }
        }
    }

    Given("a payment that is not FAILED/EXPIRED") {
        Then("termination evidence cannot be added") {
            for (status in listOf(REQUESTED, AUTHORIZING, UNKNOWN, AUTHORIZED)) {
                val p = PaymentFixture.inStatus(status)
                shouldThrow<InvalidPaymentTransitionException> { p.addTerminationEvidence(INSTITUTION_CONFIRMED_NO_EFFECT) }
                p.terminationEvidence shouldBe emptyList()
            }
        }
    }

    Given("the evidence list exposed by a payment") {
        Then("mutating it does not change the payment") {
            val p = PaymentFixture.failedWithConfirmedDecline()
            @Suppress("UNCHECKED_CAST")
            (p.terminationEvidence as? MutableList<TerminationEvidence>)?.let { runCatching { it.clear() } }
            p.terminationEvidence shouldBe listOf(INSTITUTION_CONFIRMED_DECLINE)
        }
    }
})

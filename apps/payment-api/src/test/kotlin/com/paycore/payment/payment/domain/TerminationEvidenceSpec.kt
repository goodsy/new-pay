package com.paycore.payment.payment.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * D-CPD-002 안전 종료 근거 표(상태 × 근거, 빠짐없이). 기대값은 [TerminationEvidenceOracle]의 독립 기술이다.
 */
class TerminationEvidenceSpec : BehaviorSpec({

    Given("the termination evidence kinds") {
        Then("they are exactly the D-CPD-002 sufficient kinds plus timeout, inquiry NOT_FOUND and lease expiry") {
            TerminationEvidence.entries.toSet() shouldBe
                (TerminationEvidenceOracle.sufficient.values.flatten().toSet() + TerminationEvidenceOracle.namedInsufficient)
        }
    }

    for (status in PaymentStatus.entries) {
        Given("payment status $status") {
            for (evidence in TerminationEvidence.entries) {
                val expected = TerminationEvidenceOracle.isSufficient(status, evidence)
                When("the evidence is $evidence") {
                    Then("it is ${if (expected) "" else "not "}a safe termination basis for a new payment") {
                        evidence.isSufficientFor(status) shouldBe expected
                    }
                }
            }
        }
    }
})

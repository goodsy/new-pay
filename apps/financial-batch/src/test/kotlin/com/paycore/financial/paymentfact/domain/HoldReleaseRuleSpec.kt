package com.paycore.financial.paymentfact.domain

import com.paycore.financial.paymentfact.domain.PaymentFactFixture.cancelOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * D-FFR-003 ORIGINAL_UNKNOWN 자동 해제 판정(ACQUIRING-001 §12 — HR-D1..D7). 사용자 결정: 원승인 도착 + 금액·통화·식별 MATCH → 해제, 불일치 → 운영자 검토.
 * MATCH 조건의 구체화(투영 규칙과 같은 검사 — 순번 유일·누적 ≤ 승인액 포함, 원승인 자체 보류면 검토)는 Implementer 해석 I-13이다.
 * 취소는 원승인에서 파생한 Named Fixture이고, 의도된 불일치는 `copy`로 바꾼 필드 이름에 드러난다.
 */
class HoldReleaseRuleSpec : BehaviorSpec({

    Given("HR-D1 an early partial cancel and its matching original") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        Then("it is RELEASED with no mismatch") {
            val decisions = HoldReleaseRule.decide(cancel.identity, auth, authorizationHeld = false, listOf(cancel), listOf(cancel.transactionId))
            decisions shouldBe listOf(HoldReleaseDecision(cancel.transactionId, emptySet()))
            decisions.single().outcome shouldBe HoldReleaseOutcome.RELEASED
        }
    }

    Given("HR-D2 an early cancel whose original provider transaction / authorized amount / currency differ from the arriving original") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val wrongProvider = cancel.copy(originalProviderTransactionId = "VAN-T-other")
        val wrongAuthorized = cancel.copy(transactionId = cancel.transactionId + "b", returnSequence = 2, authorizedAmountMinor = 9_999)
        Then("each is REVIEW_REQUIRED with ORIGINAL_MISMATCH (no automatic release)") {
            HoldReleaseRule.decide(cancel.identity, auth, false, listOf(wrongProvider), listOf(wrongProvider.transactionId)).single() shouldBe
                HoldReleaseDecision(wrongProvider.transactionId, setOf(HoldReleaseMismatch.ORIGINAL_MISMATCH))
            HoldReleaseRule.decide(cancel.identity, auth, false, listOf(wrongAuthorized), listOf(wrongAuthorized.transactionId)).single().outcome shouldBe
                HoldReleaseOutcome.REVIEW_REQUIRED
        }
    }

    Given("HR-D3 two early cancels of one original whose sum exceeds the authorized amount") {
        val (auth, first) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 6_000)
        val second = cancelOf(auth, 6_000, returnSequence = 2, cumulativeMinor = 12_000)
        Then("both are REVIEW_REQUIRED with RETURN_EXCEEDS_AUTHORIZED (the sum is checked across all cancels)") {
            HoldReleaseRule.decide(first.identity, auth, false, listOf(first, second), listOf(first.transactionId, second.transactionId)).map { it.mismatches } shouldBe
                listOf(setOf(HoldReleaseMismatch.RETURN_EXCEEDS_AUTHORIZED), setOf(HoldReleaseMismatch.RETURN_EXCEEDS_AUTHORIZED))
        }
    }

    Given("HR-D4 two early cancels with the same return sequence") {
        val (auth, first) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 2_000)
        val twin = cancelOf(auth, 1_000, returnSequence = 1, cumulativeMinor = 3_000, transactionId = first.transactionId + "twin")
        Then("both are REVIEW_REQUIRED with RETURN_SEQUENCE_CONFLICT") {
            HoldReleaseRule.decide(first.identity, auth, false, listOf(first, twin), listOf(first.transactionId, twin.transactionId))
                .all { HoldReleaseMismatch.RETURN_SEQUENCE_CONFLICT in it.mismatches } shouldBe true
        }
    }

    Given("HR-D5 the arriving original is itself held (e.g. IDENTITY_MISMATCH against the first fact)") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        Then("the cancel is REVIEW_REQUIRED with ORIGINAL_HELD even though amounts match") {
            HoldReleaseRule.decide(cancel.identity, auth, authorizationHeld = true, listOf(cancel), listOf(cancel.transactionId)).single() shouldBe
                HoldReleaseDecision(cancel.transactionId, setOf(HoldReleaseMismatch.ORIGINAL_HELD))
        }
    }

    Given("HR-D6 an early cancel whose payment identity differs from the payment's first fact") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val otherOrder = cancel.identity.copy(merchantOrderId = "order-other")
        Then("it is REVIEW_REQUIRED with IDENTITY_MISMATCH") {
            HoldReleaseRule.decide(otherOrder, auth, false, listOf(cancel), listOf(cancel.transactionId)).single().mismatches shouldBe
                setOf(HoldReleaseMismatch.IDENTITY_MISMATCH)
        }
    }

    Given("HR-D7 a held cancel id that is not in the history or refers to another original") {
        val (auth, cancel) = PaymentFactFixture.cancelWithoutOriginal(amountMinor = 3_000)
        val (otherAuth, _) = PaymentFactFixture.cancelWithoutOriginal()
        Then("the rule refuses (a programming error must not release anything)") {
            shouldThrow<IllegalArgumentException> { HoldReleaseRule.decide(cancel.identity, auth, false, emptyList(), listOf(cancel.transactionId)) }
            shouldThrow<IllegalArgumentException> { HoldReleaseRule.decide(cancel.identity, otherAuth, false, listOf(cancel), listOf(cancel.transactionId)) }
        }
    }
})

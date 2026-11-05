package com.paycore.financial.paymentfact.domain

import com.paycore.financial.paymentfact.domain.PaymentFactFixture.authorized
import com.paycore.financial.paymentfact.domain.PaymentFactFixture.cancelOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 결제 사실 투영 판정(FIN-FACT-RELAY-001 §4.3 — FR-D1..D10). 사용자 결정 D-ACQ-002(원거래를 모르는 취소는 Hold)와 Implementer 해석 I-5(정합성 보류)를 순수 domain으로 고정한다.
 */
class PaymentFactProjectionRuleSpec : BehaviorSpec({

    fun historyOf(vararg facts: PaymentFact): PaymentFactHistory = PaymentFactHistory(
        owner = facts.firstOrNull()?.identity,
        authorizations = facts.filterIsInstance<AuthorizedPaymentFact>(),
        cancellations = facts.filterIsInstance<CancelledPaymentFact>(),
    )

    Given("FR-D1 the first fact of a payment is an authorization") {
        val auth = authorized()
        Then("it is applied without holds and its effect key is PaymentAuthorized:<transactionId>") {
            PaymentFactProjectionRule.decide(PaymentFactHistory.EMPTY, auth) shouldBe ProjectionDecision(emptySet())
            PaymentFactProjectionRule.decide(PaymentFactHistory.EMPTY, auth).outcome shouldBe PaymentFactInboxOutcome.APPLIED
            auth.effectKey shouldBe "PaymentAuthorized:${auth.transactionId}"
        }
    }

    Given("FR-D2 a cancel whose original authorization is not projected yet (D-ACQ-002)") {
        val (_, cancel) = PaymentFactFixture.cancelWithoutOriginal()
        Then("it is HELD with ORIGINAL_UNKNOWN only — the fact is not dropped") {
            val decision = PaymentFactProjectionRule.decide(PaymentFactHistory.EMPTY, cancel)
            decision.holds shouldBe setOf(HoldReason.ORIGINAL_UNKNOWN)
            decision.outcome shouldBe PaymentFactInboxOutcome.HELD
            cancel.effectKey shouldBe "PaymentCancelled:${cancel.transactionId}"
        }
    }

    Given("FR-D3 partial cancels of a known original up to exactly the authorized amount") {
        val auth = authorized(amountMinor = 10_000)
        val first = cancelOf(auth, 3_000, returnSequence = 1, cumulativeMinor = 3_000)
        val second = cancelOf(auth, 7_000, returnSequence = 2, cumulativeMinor = 10_000)
        Then("each is applied (sum 10,000 = authorized 10,000 is not an excess)") {
            PaymentFactProjectionRule.decide(historyOf(auth), first).holds shouldBe emptySet()
            PaymentFactProjectionRule.decide(historyOf(auth, first), second).holds shouldBe emptySet()
        }
        And("one more minor unit beyond the authorized amount is held as RETURN_EXCEEDS_AUTHORIZED") {
            val excess = cancelOf(auth, 1, returnSequence = 3, cumulativeMinor = 10_001)
            PaymentFactProjectionRule.decide(historyOf(auth, first, second), excess).holds shouldBe setOf(HoldReason.RETURN_EXCEEDS_AUTHORIZED)
        }
    }

    Given("FR-D4 a cancel that does not match its original") {
        val auth = authorized(amountMinor = 10_000)
        Then("another currency is ORIGINAL_MISMATCH and is not summed") {
            val other = cancelOf(auth, 3_000).copy(money = FactMoney(3_000, "USD"))
            PaymentFactProjectionRule.decide(historyOf(auth), other).holds shouldBe setOf(HoldReason.ORIGINAL_MISMATCH)
        }
        Then("another authorized amount is ORIGINAL_MISMATCH") {
            val other = cancelOf(auth, 3_000).copy(authorizedAmountMinor = 9_999)
            PaymentFactProjectionRule.decide(historyOf(auth), other).holds shouldBe setOf(HoldReason.ORIGINAL_MISMATCH)
        }
        Then("another authorization id or institution original transaction is ORIGINAL_MISMATCH") {
            PaymentFactProjectionRule.decide(historyOf(auth), cancelOf(auth, 3_000).copy(authorizationId = "pau_other")).holds shouldBe
                setOf(HoldReason.ORIGINAL_MISMATCH)
            PaymentFactProjectionRule.decide(historyOf(auth), cancelOf(auth, 3_000).copy(originalProviderTransactionId = "VAN-T-other")).holds shouldBe
                setOf(HoldReason.ORIGINAL_MISMATCH)
        }
    }

    Given("FR-D5 two different cancels claim the same return sequence of one original") {
        val auth = authorized(amountMinor = 10_000)
        val first = cancelOf(auth, 3_000, returnSequence = 1)
        val clash = cancelOf(auth, 2_000, returnSequence = 1, cumulativeMinor = 5_000, transactionId = "ptx_cancel_clash")
        Then("the later one is held as RETURN_SEQUENCE_CONFLICT") {
            PaymentFactProjectionRule.decide(historyOf(auth, first), clash).holds shouldBe setOf(HoldReason.RETURN_SEQUENCE_CONFLICT)
        }
    }

    Given("FR-D6 a second, different authorization transaction for the same payment") {
        val auth = authorized()
        val second = authorized(paymentId = auth.identity.paymentId, transactionId = "ptx_auth_second")
        Then("it is held as AUTHORIZATION_CONFLICT; the same transaction again is not a conflict (duplicates are answered by the Inbox)") {
            PaymentFactProjectionRule.decide(historyOf(auth), second).holds shouldBe setOf(HoldReason.AUTHORIZATION_CONFLICT)
            PaymentFactProjectionRule.decide(historyOf(auth), auth).holds shouldBe emptySet()
        }
    }

    Given("FR-D7 a fact whose owner identity differs from the first fact of that payment") {
        val auth = authorized()
        val moved = cancelOf(auth, 3_000).copy(identity = auth.identity.copy(merchantAccountId = "demo-mid-002"))
        val live = cancelOf(auth, 3_000).copy(identity = auth.identity.copy(environment = FactEnvironment.LIVE))
        Then("it is held as IDENTITY_MISMATCH (MID or environment)") {
            PaymentFactProjectionRule.decide(historyOf(auth), moved).holds shouldBe setOf(HoldReason.IDENTITY_MISMATCH)
            PaymentFactProjectionRule.decide(historyOf(auth), live).holds shouldBe setOf(HoldReason.IDENTITY_MISMATCH)
        }
    }

    Given("FR-D8 values that cannot be a confirmed PAYMENT fact") {
        val auth = authorized()
        Then("non-positive amounts, malformed currency, plan combinations and impossible cancel totals are contract violations") {
            shouldThrow<PaymentFactContractViolation> { FactMoney(0, "KRW") }
            shouldThrow<PaymentFactContractViolation> { FactMoney(1, "krw") }
            shouldThrow<PaymentFactContractViolation> { FactPlan(FactPlan.LUMP_SUM, 3) }
            shouldThrow<PaymentFactContractViolation> { FactPlan(FactPlan.INSTALLMENT, 1) }
            shouldThrow<PaymentFactContractViolation> { cancelOf(auth, 3_000, cumulativeMinor = 2_999) }
            shouldThrow<PaymentFactContractViolation> { cancelOf(auth, 3_000, transactionId = auth.transactionId) }
            shouldThrow<PaymentFactContractViolation> { auth.copy(cardType = "DEBIT") }
        }
        Then("identifiers follow the PAYMENT column rules: well-formed text (non-ASCII allowed), no control characters, at most 64 characters") {
            identityWithSite("사이트-001").merchantSiteId shouldBe "사이트-001"
            shouldThrow<PaymentFactContractViolation> { identityWithSite("site\u0000") }
            shouldThrow<PaymentFactContractViolation> { identityWithSite("s".repeat(65)) }
            shouldThrow<PaymentFactContractViolation> { identityWithSite("") }
            shouldThrow<PaymentFactContractViolation> { identityWithSite("\uD800x") }
        }
    }

    Given("FR-D9 supported event types") {
        Then("only PaymentAuthorized and PaymentCancelled schema 1 are supported; anything else is UNSUPPORTED (kept, not projected)") {
            PaymentFactKind.supported("PaymentAuthorized", 1) shouldBe PaymentFactKind.AUTHORIZATION
            PaymentFactKind.supported("PaymentCancelled", 1) shouldBe PaymentFactKind.CANCELLATION
            PaymentFactKind.supported("PaymentAuthorized", 2) shouldBe null
            PaymentFactKind.supported("PaymentRefunded", 1) shouldBe null
        }
    }
}) {
    private companion object {
        fun identityWithSite(site: String) = PaymentFactFixture.identity().copy(merchantSiteId = "x").let {
            PaymentFactIdentity(it.environment, it.merchantAccountId, site, it.merchantOrderId, it.paymentId)
        }
    }
}

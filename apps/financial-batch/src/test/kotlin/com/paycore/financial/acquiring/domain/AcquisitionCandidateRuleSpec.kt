package com.paycore.financial.acquiring.domain

import com.paycore.financial.acquiring.domain.AcquisitionFixture.CUTOFF
import com.paycore.financial.acquiring.domain.AcquisitionFixture.cancellationOf
import com.paycore.financial.acquiring.domain.AcquisitionFixture.sale
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * 매입 후보 판정(AQ-D1..D8). 사용자 결정 D-ACQ-002(승인 확정 건만·전송 전 전액 취소 제외·부분 취소 상계·원거래 모르는 취소 보류)·D-ACQ-008(LIVE 차단)·
 * D-ACQ-012(판정 시점에 받은 취소만 상계)와 Implementer 해석 I-4(보류 대기)·I-5(KRW 외 대기)를 순수 domain으로 고정한다.
 */
class AcquisitionCandidateRuleSpec : BehaviorSpec({

    Given("AQ-D1 a KRW authorization approved before the cutoff with no cancel") {
        val s = sale(amountMinor = 10_000)
        Then("it is included as a SALE record of the full amount (merchantRef = MID — D-ACQ-008)") {
            val decision = AcquisitionCandidateRule.decide(s, CUTOFF).shouldBeInstanceOf<CandidateDecision.Include>()
            val record = decision.toRecord(1)
            listOf(record.type, record.recordAmountMinor, record.nettedCancelAmountMinor, record.merchantRef, record.generation) shouldBe
                listOf(AcquisitionRecordType.SALE, 10_000L, 0L, "demo-mid-001", 0)
        }
    }

    Given("AQ-D2 partial cancels received before membership (D-ACQ-002 상계, D-ACQ-012)") {
        val base = sale(amountMinor = 10_000)
        val s = base.copy(cancellations = listOf(cancellationOf(base, 3_000, 1), cancellationOf(base, 2_000, 2)))
        Then("the SALE record carries approved 10,000, netted 5,000 and record amount 5,000 with both cancels netted") {
            val decision = AcquisitionCandidateRule.decide(s, CUTOFF).shouldBeInstanceOf<CandidateDecision.Include>()
            val record = decision.toRecord(1)
            listOf(record.approvedAmountMinor, record.nettedCancelAmountMinor, record.recordAmountMinor) shouldBe listOf(10_000L, 5_000L, 5_000L)
            decision.netted.map { it.transactionId } shouldBe s.cancellations.map { it.transactionId }
        }
    }

    Given("AQ-D3 cancels received before membership add up to the full amount") {
        val base = sale(amountMinor = 10_000)
        val s = base.copy(cancellations = listOf(cancellationOf(base, 4_000, 1), cancellationOf(base, 6_000, 2)))
        Then("it is excluded (FULLY_CANCELLED_BEFORE_SUBMISSION) with both cancels netted — no record") {
            val decision = AcquisitionCandidateRule.decide(s, CUTOFF).shouldBeInstanceOf<CandidateDecision.Exclude>()
            listOf(decision.reason, decision.nettedAmountMinor, decision.netted.size) shouldBe listOf(ExclusionReason.FULLY_CANCELLED_BEFORE_SUBMISSION, 10_000L, 2)
        }
    }

    Given("AQ-D4 holds (fail-closed — Implementer I-4, D-ACQ-002 원거래 모르는 취소 보류)") {
        val base = sale()
        Then("a held authorization or a held cancel of it waits (nothing recorded)") {
            AcquisitionCandidateRule.decide(base.copy(held = true, paymentHeld = true), CUTOFF) shouldBe CandidateDecision.Wait(WaitReason.AUTHORIZATION_HELD)
            AcquisitionCandidateRule.decide(base.copy(cancellations = listOf(cancellationOf(base, 1_000, held = true)), paymentHeld = true), CUTOFF) shouldBe
                CandidateDecision.Wait(WaitReason.CANCELLATION_HELD)
        }
        Then("an unheld approval whose payment has any other unreleased hold waits too (PAYMENT_HELD — Coordinator decision, review MJ-2)") {
            AcquisitionCandidateRule.decide(base.copy(paymentHeld = true), CUTOFF) shouldBe CandidateDecision.Wait(WaitReason.PAYMENT_HELD)
        }
        Then("a candidate cannot claim its own hold without its payment being held (input invariant)") {
            runCatching { base.copy(held = true, paymentHeld = false) }.exceptionOrNull().shouldBeInstanceOf<AcquisitionContractViolation>()
        }
    }

    Given("AQ-D9 PAYMENT-confirmed values that file v1 cannot carry (review MJ-1 — the fact is not refused, only this transaction waits)") {
        Then("a space in the approval number, a lower-case issuer code or a field delimiter in the MID wait as UNREPRESENTABLE instead of failing the seal") {
            listOf(
                sale(approvalNumber = "A 01"),
                sale().copy(issuerCode = "simissa"),
                sale().copy(merchantAccountId = "mid|1"),
                sale().copy(approvedAt = Instant.parse("2026-11-05T01:02:03.456789Z")),
            ).map { AcquisitionCandidateRule.decide(it, CUTOFF) }.toSet() shouldBe setOf(CandidateDecision.Wait(WaitReason.UNREPRESENTABLE))
        }
    }

    Given("AQ-D5 LIVE, after-cutoff and non-KRW authorizations") {
        Then("LIVE waits (D-ACQ-008), after the cutoff waits for the next business day, non-KRW waits (I-5 — no terminal decision)") {
            AcquisitionCandidateRule.decide(sale(environment = AcquisitionEnvironment.LIVE), CUTOFF) shouldBe CandidateDecision.Wait(WaitReason.LIVE_BLOCKED)
            AcquisitionCandidateRule.decide(sale(approvedAt = CUTOFF.plusMillis(1)), CUTOFF) shouldBe CandidateDecision.Wait(WaitReason.AFTER_CUTOFF)
            AcquisitionCandidateRule.decide(sale(approvedAt = CUTOFF), CUTOFF).shouldBeInstanceOf<CandidateDecision.Include>()
            AcquisitionCandidateRule.decide(sale(currency = "USD"), CUTOFF) shouldBe CandidateDecision.Wait(WaitReason.UNSUPPORTED_CURRENCY)
        }
    }

    Given("AQ-D6 inconsistent inputs that the consistency holds should already have caught") {
        val base = sale(amountMinor = 10_000)
        Then("cancels above the original or in another currency wait instead of producing a negative or mixed record") {
            AcquisitionCandidateRule.decide(base.copy(cancellations = listOf(cancellationOf(base, 10_001))), CUTOFF) shouldBe CandidateDecision.Wait(WaitReason.INCONSISTENT)
            AcquisitionCandidateRule.decide(base.copy(cancellations = listOf(cancellationOf(base, 1_000).copy(currency = "USD"))), CUTOFF) shouldBe
                CandidateDecision.Wait(WaitReason.INCONSISTENT)
        }
    }

    Given("AQ-D7 a post-membership cancel's CANCEL record decision (D-ACQ-009)") {
        Then("only an ACCEPTED original sale gets a CANCEL record; awaiting waits; UNKNOWN/CONFLICT go to reconciliation; REJECTED is 'not required'") {
            OriginalSaleResult.entries.associateWith(CancelRecordRule::decide) shouldBe mapOf(
                OriginalSaleResult.AWAITING to CancelRecordDecision.WAIT,
                OriginalSaleResult.ACCEPTED to CancelRecordDecision.INCLUDE,
                OriginalSaleResult.REJECTED to CancelRecordDecision.NOT_REQUIRED,
                OriginalSaleResult.UNKNOWN to CancelRecordDecision.RECONCILE,
                OriginalSaleResult.CONFLICT to CancelRecordDecision.RECONCILE,
            )
        }
    }

    Given("AQ-D8 the versioned cutoff policy (D-ACQ-003 — Asia/Seoul, Demo values)") {
        val policy = CutoffPolicy(
            listOf(
                CutoffPolicyVersion("v2", java.time.LocalDate.parse("2026-12-01"), java.time.LocalTime.parse("22:00")),
                CutoffPolicyVersion("v1", java.time.LocalDate.parse("2026-01-01"), java.time.LocalTime.parse("23:00")),
            ),
        )
        Then("the latest version in effect applies, the cutoff is the Seoul local time, and a date before any version is refused") {
            policy.versionFor(java.time.LocalDate.parse("2026-11-30")).version shouldBe "v1"
            policy.versionFor(java.time.LocalDate.parse("2026-12-01")).version shouldBe "v2"
            policy.cutoffInstant(java.time.LocalDate.parse("2026-11-05")) shouldBe Instant.parse("2026-11-05T14:00:00Z")
            policy.cutoffInstant(java.time.LocalDate.parse("2026-12-01")) shouldBe Instant.parse("2026-12-01T13:00:00Z")
            runCatching { policy.versionFor(java.time.LocalDate.parse("2025-12-31")) }.exceptionOrNull().shouldBeInstanceOf<AcquisitionContractViolation>()
        }
    }
})

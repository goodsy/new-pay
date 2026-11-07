package com.paycore.financial.acquiring.domain

import com.paycore.financial.acquiring.domain.AcquisitionFixture.NEXT_CUTOFF
import com.paycore.financial.acquiring.domain.AcquisitionFixture.cancelAfterMembership
import com.paycore.financial.acquiring.domain.AcquisitionFixture.cancelAfterReacquisition
import com.paycore.financial.acquiring.domain.AcquisitionFixture.header
import com.paycore.financial.acquiring.domain.AcquisitionFixture.sale
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.time.LocalDate

/**
 * 전송 후 취소의 봉인 판정(CC-1..CC-7 — D-ACQ-009 연결, 리뷰 NIT-3 거래 단위 대기). 사용자 결정 D-ACQ-009(원 SALE ACCEPTED일 때만 CANCEL, 대기 → 대기, UNKNOWN → 대사,
 * REJECTED → 취소 매입 불요)와 Implementer 해석 I-47(보류·통화·비정합·표현 불가 = 그 취소만 대기)·I-48(CANCEL 레코드 값)을 고정한다.
 */
class CancelCandidateRuleSpec : BehaviorSpec({

    Given("CC-1 a cancellation after membership whose original SALE is ACCEPTED") {
        val c = cancelAfterMembership(original = sale(amountMinor = 10_000), amountMinor = 3_000)
        val decision = CancelCandidateRule.decide(c, OriginalSaleResult.ACCEPTED, NEXT_CUTOFF).shouldBeInstanceOf<CancelDecision.Include>()
        Then("it becomes a CANCEL record: original reference, cancel amount, original approved amount and approval number (I-48), no netting, generation 0") {
            val r = decision.toRecord(3)
            listOf(r.seq, r.type, r.transactionId, r.originalTransactionId, r.originalProviderTransactionId, r.approvedAmountMinor, r.recordAmountMinor, r.nettedCancelAmountMinor) shouldBe
                listOf(3, AcquisitionRecordType.CANCEL, c.transactionId, c.original.transactionId, c.original.providerTransactionId, 10_000L, 3_000L, 0L)
            listOf(r.approvalNumber, r.merchantRef, r.eventAt, r.institutionTransactionDate, r.generation) shouldBe
                listOf(c.original.approvalNumber, c.original.merchantAccountId, c.cancelledAt, LocalDate.parse("2026-11-06"), 0)
        }
    }

    Given("CC-2 the original SALE result is not final or not positive (D-ACQ-009)") {
        val c = cancelAfterMembership()
        Then("AWAITING → wait (ORIGINAL_AWAITING_RESULT), UNKNOWN·CONFLICT → reconcile (not sent), REJECTED → not required") {
            CancelCandidateRule.decide(c, OriginalSaleResult.AWAITING, NEXT_CUTOFF) shouldBe CancelDecision.Wait(WaitReason.ORIGINAL_AWAITING_RESULT)
            CancelCandidateRule.decide(c, OriginalSaleResult.UNKNOWN, NEXT_CUTOFF) shouldBe CancelDecision.Reconcile(c, OriginalSaleResult.UNKNOWN)
            CancelCandidateRule.decide(c, OriginalSaleResult.CONFLICT, NEXT_CUTOFF) shouldBe CancelDecision.Reconcile(c, OriginalSaleResult.CONFLICT)
            CancelCandidateRule.decide(c, OriginalSaleResult.REJECTED, NEXT_CUTOFF) shouldBe CancelDecision.NotRequired(c)
        }
    }

    Given("CC-3 transaction-level waits apply to CANCEL like to SALE (review NIT-3), before the original result is looked at") {
        Then("held cancellation, held payment, LIVE, after the cutoff and non-KRW wait; none of them is NOT_REQUIRED even if the original was rejected") {
            listOf(
                cancelAfterMembership(original = sale(paymentHeld = true), held = true) to WaitReason.CANCELLATION_HELD,
                cancelAfterMembership(original = sale(paymentHeld = true)) to WaitReason.PAYMENT_HELD,
                cancelAfterMembership(original = sale(environment = AcquisitionEnvironment.LIVE)) to WaitReason.LIVE_BLOCKED,
                cancelAfterMembership(cancelledAt = NEXT_CUTOFF.plusMillis(1)) to WaitReason.AFTER_CUTOFF,
                cancelAfterMembership().let { it.copy(currency = "USD") } to WaitReason.UNSUPPORTED_CURRENCY,
            ).forEach { (c, reason) ->
                CancelCandidateRule.decide(c, OriginalSaleResult.REJECTED, NEXT_CUTOFF) shouldBe CancelDecision.Wait(reason)
                CancelCandidateRule.decide(c, OriginalSaleResult.ACCEPTED, NEXT_CUTOFF) shouldBe CancelDecision.Wait(reason)
            }
        }
    }

    Given("CC-4 cancellations that disagree with the original or exceed what is left of it") {
        val original = sale(amountMinor = 10_000)
        Then("netted + already cancelled + this > approved, other original institution reference, other MID or a non-SALE original record wait as INCONSISTENT") {
            // ACQ-ADMIN-001 rev 0.7(B2 — §15 L-6): 이 목록의 넷째 벡터(세대 1 원 SALE → INCONSISTENT)는 CC-8로 옮겼다. 재매입 세대 SALE은 이제 같은 정합 규칙으로 판정한다
            // (기대값 변경 근거 = L-6 "새 세대 결과에 따른 CANCEL·불요 판정은 B2에서 CancelCandidateRule을 바꿔 연다"). 그 자리에 원 레코드 종류·금액 불일치 벡터를 둔다(강화).
            listOf(
                cancelAfterMembership(original, amountMinor = 3_000, nettedMinor = 4_000, alreadyCancelledMinor = 3_001),
                cancelAfterMembership(original).copy(originalProviderTransactionId = "VAN-T-other"),
                cancelAfterMembership(original).copy(merchantAccountId = "demo-mid-002"),
                cancelAfterMembership(original).let {
                    it.copy(originalSale = it.originalSale.copy(type = AcquisitionRecordType.CANCEL, originalTransactionId = "ptx_x", originalProviderTransactionId = "VAN-x",
                        nettedCancelAmountMinor = 0, recordAmountMinor = 1_000))
                },
                cancelAfterMembership(original).let { it.copy(originalSale = it.originalSale.copy(approvedAmountMinor = 10_001, recordAmountMinor = 10_001)) },
            ).forEach { CancelCandidateRule.decide(it, OriginalSaleResult.ACCEPTED, NEXT_CUTOFF) shouldBe CancelDecision.Wait(WaitReason.INCONSISTENT) }
            // 경계: 남은 금액 전부는 받는다.
            CancelCandidateRule.decide(cancelAfterMembership(original, amountMinor = 3_000, nettedMinor = 4_000, alreadyCancelledMinor = 3_000), OriginalSaleResult.ACCEPTED, NEXT_CUTOFF)
                .shouldBeInstanceOf<CancelDecision.Include>()
        }
    }

    Given("CC-8 (ACQ-ADMIN-001 B2 — §15 L-6) a cancellation received after the original was re-acquired: judged against the highest generation SALE (I-14)") {
        val original = sale(amountMinor = 10_000)
        // 재매입 실행 시점까지 판정 끝난 취소 3,000(상계액 — I-13) → 재매입 레코드 7,000. 이 취소 2,000은 실행 뒤에 받았다.
        val c = cancelAfterReacquisition(original, amountMinor = 2_000, nettedMinor = 3_000)
        Then("the new generation's result decides it exactly like generation 0 (D-ACQ-009): ACCEPTED → CANCEL record, REJECTED → not required, AWAITING → wait, UNKNOWN → reconcile") {
            // 기대값 변경(rev 0.7 — 근거 L-6): rev 0.6까지 세대 ≥ 1 원 SALE은 결과와 무관하게 INCONSISTENT 대기였다(CC-4 넷째 벡터).
            val include = CancelCandidateRule.decide(c, OriginalSaleResult.ACCEPTED, NEXT_CUTOFF).shouldBeInstanceOf<CancelDecision.Include>()
            val r = include.toRecord(1)
            listOf(r.type, r.originalTransactionId, r.approvedAmountMinor, r.recordAmountMinor, r.nettedCancelAmountMinor, r.generation, r.reacquisitionOf) shouldBe
                listOf(AcquisitionRecordType.CANCEL, original.transactionId, 10_000L, 2_000L, 0L, 0, null)
            CancelCandidateRule.decide(c, OriginalSaleResult.REJECTED, NEXT_CUTOFF) shouldBe CancelDecision.NotRequired(c)
            CancelCandidateRule.decide(c, OriginalSaleResult.AWAITING, NEXT_CUTOFF) shouldBe CancelDecision.Wait(WaitReason.ORIGINAL_AWAITING_RESULT)
            CancelCandidateRule.decide(c, OriginalSaleResult.UNKNOWN, NEXT_CUTOFF) shouldBe CancelDecision.Reconcile(c, OriginalSaleResult.UNKNOWN)
            CancelCandidateRule.decide(cancelAfterReacquisition(original, generation = 2, nettedMinor = 3_000), OriginalSaleResult.ACCEPTED, NEXT_CUTOFF)
                .shouldBeInstanceOf<CancelDecision.Include>()
        }
        Then("the cumulative limit still holds across generations: netted before the execution + CANCEL records + this ≤ approved, else INCONSISTENT (not relaxed)") {
            CancelCandidateRule.decide(cancelAfterReacquisition(original, amountMinor = 2_001, nettedMinor = 3_000, alreadyCancelledMinor = 5_000), OriginalSaleResult.ACCEPTED, NEXT_CUTOFF) shouldBe
                CancelDecision.Wait(WaitReason.INCONSISTENT)
            CancelCandidateRule.decide(cancelAfterReacquisition(original, amountMinor = 2_000, nettedMinor = 3_000, alreadyCancelledMinor = 5_000), OriginalSaleResult.ACCEPTED, NEXT_CUTOFF)
                .shouldBeInstanceOf<CancelDecision.Include>()
            CancelCandidateRule.decide(cancelAfterReacquisition(original, amountMinor = 2_001, nettedMinor = 8_000), OriginalSaleResult.REJECTED, NEXT_CUTOFF) shouldBe
                CancelDecision.Wait(WaitReason.INCONSISTENT)
        }
        Then("a re-acquisition record that is not this transaction's SALE at the original approved amount is INCONSISTENT") {
            listOf(
                c.copy(originalSale = c.originalSale.copy(transactionId = "ptx_other")),
                c.copy(originalSale = c.originalSale.copy(approvedAmountMinor = 9_000, recordAmountMinor = 6_000)),
            ).forEach { CancelCandidateRule.decide(it, OriginalSaleResult.ACCEPTED, NEXT_CUTOFF) shouldBe CancelDecision.Wait(WaitReason.INCONSISTENT) }
        }
    }

    Given("CC-5 a confirmed cancellation value that file v1 cannot carry (review NIT-3 — UNREPRESENTABLE, transaction-level)") {
        Then("a provider reference with a space waits; the lane is not failed") {
            CancelCandidateRule.decide(cancelAfterMembership().copy(providerTransactionId = "VAN C 1"), OriginalSaleResult.ACCEPTED, NEXT_CUTOFF) shouldBe
                CancelDecision.Wait(WaitReason.UNREPRESENTABLE)
        }
    }

    Given("CC-6 a file sealed with SALE and CANCEL members") {
        val nextDay = header(AcquisitionFileId("SIMACQA", AcquisitionEnvironment.TEST, LocalDate.parse("2026-11-06"), 1), Instant.parse("2026-11-06T14:05:00.000Z"))
        val s = sale("ptx_next", 7_000, approvedAt = Instant.parse("2026-11-06T01:00:00.000Z"))
        val late = cancelAfterMembership(n = 2, cancelledAt = Instant.parse("2026-11-06T05:00:00.000Z"))
        val early = cancelAfterMembership(n = 1, cancelledAt = Instant.parse("2026-11-06T03:00:00.000Z"))
        val sameDay = AcquisitionCandidateRule.decide(s, NEXT_CUTOFF) as CandidateDecision.Include
        val (file, rendered) = AcquisitionFile.seal(nextDay, NEXT_CUTOFF, listOf(sameDay), listOf(CancelDecision.Include(late), CancelDecision.Include(early)))
        Then("SALE records come first, then CANCEL records by cancel time; the trailer nets cancels out of the sale total and the bytes parse back") {
            rendered.records.map { it.seq to it.type } shouldBe listOf(1 to AcquisitionRecordType.SALE, 2 to AcquisitionRecordType.CANCEL, 3 to AcquisitionRecordType.CANCEL)
            rendered.records.drop(1).map { it.transactionId } shouldBe listOf(early.transactionId, late.transactionId)
            listOf(file.totals.saleAmountMinor, file.totals.cancelAmountMinor, file.totals.netAmountMinor) shouldBe listOf(7_000L, 6_000L, 1_000L)
            AcquisitionFileFormat.parse(rendered.content).records shouldBe rendered.records
        }
    }

    Given("CC-7 card types are the file v1 set (review NIT-2 — the token grammar was wider than card_type VARCHAR(8))") {
        Then("a card type outside CREDIT/CHECK makes the SALE wait UNREPRESENTABLE instead of failing the seal INSERT") {
            AcquisitionCandidateRule.decide(sale().copy(cardType = "PREPAIDCARD"), AcquisitionFixture.CUTOFF) shouldBe CandidateDecision.Wait(WaitReason.UNREPRESENTABLE)
            AcquisitionCandidateRule.decide(sale().copy(cardType = "CHECK"), AcquisitionFixture.CUTOFF).shouldBeInstanceOf<CandidateDecision.Include>()
        }
    }
})

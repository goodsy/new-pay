package com.paycore.simulator.van.domain

import com.paycore.simulator.van.domain.VanCancelFixture.approvedAt
import com.paycore.simulator.van.domain.VanCancelFixture.cancel
import com.paycore.simulator.van.domain.VanCancelFixture.ids
import com.paycore.simulator.van.domain.VanCancelFixture.now
import com.paycore.simulator.van.domain.VanCancelFixture.originalApproved
import com.paycore.simulator.van.domain.VanCancelFixture.request
import com.paycore.simulator.van.domain.VanFixture.profileA
import com.paycore.simulator.van.domain.VanFixture.profileB
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.LocalDate

/**
 * 가상 VAN 취소 판정(CANCEL-001 §6.3 — 앞 단계가 결정하면 멈춤, 순수 함수). 기대값은 명세 §6.3·§6.4 표에서 직접 적는다: 같은 요청 ID 재전송·충돌·종결 표지, TEST 아님·보존기간 밖
 * 요청 거절, 원 승인 없음·승인 아님·echo 불일치, Demo 취소 기한, 부분취소 지원(원 Plan·cardType)·scope echo, 기관 잔액(이미 승인된 취소 합 Σ — 한 스냅샷 n·Σ, m-6), 가상 카드 시나리오.
 * APPROVED는 순번 n + 1로 기록한다(같은 원 승인의 동시 취소는 순번 UNIQUE가 직렬화 — CN-22).
 */
class VanCancellationSpec : BehaviorSpec({

    fun decided(j: VanCancelJudgement): VanCancelJudgement.Decide = j.shouldBeInstanceOf<VanCancelJudgement.Decide>()
    fun declined(j: VanCancelJudgement): VanCancelDeclineCategory? = decided(j).entry.declineCategory

    Given("an APPROVED original authorization in the VAN ledger") {
        Then("a full cancel is APPROVED with sequence 1 and the cancel terms (cancelled amount, original PTX, new PTX, approval number, Seoul dates)") {
            val original = originalApproved()
            val r = request(original)
            val d = decided(cancel(request = r, original = original))
            d.behaviour shouldBe VanCancelBehaviour.NORMAL
            val e = d.entry
            e.kind shouldBe VanEntryKind.DECISION
            e.entryId shouldBe ids.decisionId
            e.namespace shouldBe VanNamespace(VanFixture.CLIENT, "TEST", "SIMMERCH0001", VanNamespace.CANCEL, r.requestId)
            e.payloadHash shouldBe r.payloadHash()
            e.originalRequestId shouldBe original.namespace.requestId
            e.cardRef shouldBe "VC-CREDIT-OK"
            e.outcome shouldBe VanDecisionOutcome.APPROVED
            e.approvedSequence shouldBe 1
            e.terms shouldBe VanCancelTerms(
                cancelApprovalNumber = "40000001", providerTransactionId = "SIMPTX-C000000001", originalProviderTransactionId = "SIMPTX-0000000001",
                cancelledAmountMinor = 10_000, currency = "KRW", institutionTransactionDate = VanDates.seoulDate(now), institutionBusinessDate = VanDates.seoulDate(now),
            )
            e.createdAt shouldBe now
        }
        Then("a partial cancel (amount below the original) with scope PARTIAL is APPROVED with sequence 1") {
            val e = decided(cancel(request = request(amountMinor = 3_000, scope = VanCancelScope.PARTIAL))).entry
            e.outcome shouldBe VanDecisionOutcome.APPROVED
            e.approvedSequence shouldBe 1
            e.terms?.cancelledAmountMinor shouldBe 3_000
        }
        Then("after an approved cancel (n = 1, Σ = 3000) the next partial gets sequence 2, and the exact remainder is still PARTIAL") {
            val after = VanApprovedCancels(count = 1, sumMinor = 3_000)
            decided(cancel(request = request(amountMinor = 3_000, scope = VanCancelScope.PARTIAL), approved = after)).entry.approvedSequence shouldBe 2
            decided(cancel(request = request(amountMinor = 7_000, scope = VanCancelScope.PARTIAL), approved = after)).entry.approvedSequence shouldBe 2
            declined(cancel(request = request(amountMinor = 7_000, scope = VanCancelScope.FULL), approved = after)) shouldBe VanCancelDeclineCategory.ORIGINAL_MISMATCH
        }
        Then("an amount above the remaining balance is DECLINED EXCEEDS_REMAINING and takes no sequence (institution balance — CC §4.6)") {
            val after = VanApprovedCancels(count = 1, sumMinor = 3_000)
            val e = decided(cancel(request = request(amountMinor = 7_001, scope = VanCancelScope.PARTIAL), approved = after)).entry
            e.declineCategory shouldBe VanCancelDeclineCategory.EXCEEDS_REMAINING
            e.approvedSequence shouldBe null
            e.terms shouldBe null
            declined(cancel(request = request(amountMinor = 10_001, scope = VanCancelScope.FULL))) shouldBe VanCancelDeclineCategory.EXCEEDS_REMAINING
            declined(cancel(request = request(amountMinor = 1, scope = VanCancelScope.PARTIAL), approved = VanApprovedCancels(2, 10_000))) shouldBe
                VanCancelDeclineCategory.EXCEEDS_REMAINING
        }
        Then("a scope echo that disagrees with the VAN's own full/partial judgement is ORIGINAL_MISMATCH") {
            declined(cancel(request = request(amountMinor = 3_000, scope = VanCancelScope.FULL))) shouldBe VanCancelDeclineCategory.ORIGINAL_MISMATCH
            declined(cancel(request = request(amountMinor = 10_000, scope = VanCancelScope.PARTIAL))) shouldBe VanCancelDeclineCategory.ORIGINAL_MISMATCH
        }
        Then("an echo of the original PTX, approval number, currency or transaction date that differs is ORIGINAL_MISMATCH") {
            listOf(
                request(originalProviderTransactionId = "SIMPTX-0000000002"),
                request(originalApprovalNumber = "30000002"),
                request(currency = "USD"),
                request(originalTransactionDate = VanDates.seoulDate(approvedAt).minusDays(1)),
            ).forEach { r -> declined(cancel(request = r)) shouldBe VanCancelDeclineCategory.ORIGINAL_MISMATCH }
        }
    }

    Given("no usable original authorization") {
        Then("no ledger row or a closure tombstone is ORIGINAL_NOT_FOUND; a declined original is ORIGINAL_NOT_APPROVED") {
            declined(cancel(original = null)) shouldBe VanCancelDeclineCategory.ORIGINAL_NOT_FOUND
            val closure = VanCancelFixture.originalClosure()
            declined(cancel(request = request(original = closure, amountMinor = 10_000), original = closure)) shouldBe VanCancelDeclineCategory.ORIGINAL_NOT_FOUND
            val declinedOriginal = VanCancelFixture.originalDeclined()
            declined(cancel(request = request(original = declinedOriginal, amountMinor = 10_000), original = declinedOriginal)) shouldBe
                VanCancelDeclineCategory.ORIGINAL_NOT_APPROVED
        }
        Then("a declined cancel is still a recorded decision (same ID resend = same decline)") {
            val e = decided(cancel(original = null)).entry
            e.kind shouldBe VanEntryKind.DECISION
            e.outcome shouldBe VanDecisionOutcome.DECLINED
            e.payloadHash shouldBe request().payloadHash()
        }
    }

    Given("the Demo cancel window (profile cancel.window = 365 days from the original decision)") {
        Then("exactly at the window it is still cancellable, one millisecond later it is DECLINED CANCEL_PERIOD_EXPIRED (D-CANCEL-004)") {
            val edge = approvedAt.plus(Duration.ofDays(365))
            // 취소 요청 ID는 판정 시각에 만든 것(보존기간 안) — 기한만 바꾼다.
            fun at(t: java.time.Instant) = request(requestId = "cr_" + VanUlid.format(t.minusSeconds(1), 31))
            decided(cancel(request = at(edge), at = edge)).entry.outcome shouldBe VanDecisionOutcome.APPROVED
            declined(cancel(request = at(edge.plusMillis(1)), at = edge.plusMillis(1))) shouldBe VanCancelDeclineCategory.CANCEL_PERIOD_EXPIRED
        }
        Then("the window is judged before partial support and the balance") {
            val late = approvedAt.plus(Duration.ofDays(366))
            val r = request(original = originalApproved(cardType = "CHECK"), amountMinor = 3_000, scope = VanCancelScope.PARTIAL, requestId = "cr_" + VanUlid.format(late, 32))
            declined(cancel(request = r, original = originalApproved(cardType = "CHECK"), profile = profileB, at = late)) shouldBe
                VanCancelDeclineCategory.CANCEL_PERIOD_EXPIRED
        }
    }

    Given("partial-cancel support of the profile per (original plan, card type)") {
        Then("profile A supports credit/check lump sum and credit installment partial cancels") {
            listOf(originalApproved(cardType = "CHECK"), originalApproved(plan = VanPlan.installment(3))).forEach { o ->
                decided(cancel(request = request(original = o, amountMinor = 3_000, scope = VanCancelScope.PARTIAL), original = o)).entry.outcome shouldBe
                    VanDecisionOutcome.APPROVED
            }
        }
        Then("profile B refuses a check lump-sum or a credit installment partial cancel (PARTIAL_NOT_SUPPORTED), but a full cancel of them is APPROVED") {
            listOf(originalApproved(cardType = "CHECK"), originalApproved(plan = VanPlan.installment(3))).forEach { o ->
                declined(cancel(request = request(original = o, amountMinor = 3_000, scope = VanCancelScope.PARTIAL), original = o, profile = profileB)) shouldBe
                    VanCancelDeclineCategory.PARTIAL_NOT_SUPPORTED
                decided(cancel(request = request(original = o), original = o, profile = profileB)).entry.outcome shouldBe VanDecisionOutcome.APPROVED
            }
        }
        Then("a remainder after an earlier cancel is partial too, so profile B refuses it for a check card") {
            val o = originalApproved(cardType = "CHECK")
            declined(cancel(request = request(original = o, amountMinor = 7_000, scope = VanCancelScope.PARTIAL), original = o, profile = profileB,
                approved = VanApprovedCancels(1, 3_000))) shouldBe VanCancelDeclineCategory.PARTIAL_NOT_SUPPORTED
        }
    }

    Given("cancel scenario cards (the original authorization's card reference — CN-25)") {
        Then("VC-CXL-DECLINE → DECLINED ISSUER_DECLINED; VC-CXL-DROP-BEFORE → closes without a record") {
            val decline = originalApproved(cardRef = "VC-CXL-DECLINE")
            declined(cancel(request = request(decline), original = decline)) shouldBe VanCancelDeclineCategory.ISSUER_DECLINED
            val drop = originalApproved(cardRef = "VC-CXL-DROP-BEFORE")
            cancel(request = request(drop), original = drop) shouldBe VanCancelJudgement.DropWithoutRecord
        }
        Then("VC-CXL-PARTIAL is APPROVED for one minor unit less than requested") {
            val o = originalApproved(cardRef = "VC-CXL-PARTIAL")
            decided(cancel(request = request(o), original = o)).entry.terms?.cancelledAmountMinor shouldBe 9_999
        }
        Then("transport cards are APPROVED and carry their first-response behaviour") {
            mapOf(
                "VC-CXL-LATE-RESPONSE" to VanCancelBehaviour.LATE_RESPONSE,
                "VC-CXL-DROP-AFTER" to VanCancelBehaviour.DROP_AFTER,
                "VC-CXL-BAD-MAC" to VanCancelBehaviour.BAD_MAC,
                "VC-CXL-FRAGMENTED" to VanCancelBehaviour.FRAGMENTED,
                "VC-CXL-INQUIRY-UNAVAILABLE-ONCE" to VanCancelBehaviour.INQUIRY_UNAVAILABLE_ONCE,
            ).forEach { (card, behaviour) ->
                val o = originalApproved(cardRef = card)
                val d = decided(cancel(request = request(o), original = o))
                d.entry.outcome shouldBe VanDecisionOutcome.APPROVED
                d.behaviour shouldBe behaviour
            }
        }
        Then("business rules come before the card: a decline card over the balance is EXCEEDS_REMAINING") {
            val o = originalApproved(cardRef = "VC-CXL-DECLINE")
            declined(cancel(request = request(o, amountMinor = 10_001), original = o)) shouldBe VanCancelDeclineCategory.EXCEEDS_REMAINING
        }
    }

    Given("the cancel scenario card catalog (CN-25)") {
        Then("every cancel scenario card is a virtual card of the authentication catalog, with a CREDIT card type and a normal authentication") {
            VanCancelBehaviour.cardRefs.forEach { ref ->
                val card = checkNotNull(com.paycore.simulator.authentication.domain.VirtualCard.fromRef(ref)) { ref }
                card.cardType shouldBe "CREDIT"
                card.scenario shouldBe com.paycore.simulator.authentication.domain.CardScenario.AUTHENTICATE
            }
            VanCancelBehaviour.cardRefs.size shouldBe 8
        }
    }

    Given("a request ID already recorded in the cancel namespace") {
        val first = decided(cancel()).entry
        Then("the same payload replays the decision, a different payload is REQUEST_CONFLICT (no new decision)") {
            cancel(existing = first).shouldBeInstanceOf<VanCancelJudgement.Replay>().entry shouldBe first
            cancel(request = request(amountMinor = 3_000, scope = VanCancelScope.PARTIAL), existing = first) shouldBe VanCancelJudgement.Conflict
        }
        Then("the replay is judged before the balance and the window (a later approved cancel or expiry does not change a recorded decision)") {
            cancel(existing = first, approved = VanApprovedCancels(1, 10_000), at = approvedAt.plus(Duration.ofDays(400)))
                .shouldBeInstanceOf<VanCancelJudgement.Replay>()
        }
        Then("a closure tombstone is REJECTED_CLOSED for any payload") {
            val closure = VanCancelEntry.closure(first.namespace, ids.closureId, now)
            cancel(existing = closure) shouldBe VanCancelJudgement.RejectedClosed
            cancel(request = request(amountMinor = 1, scope = VanCancelScope.PARTIAL), existing = closure) shouldBe VanCancelJudgement.RejectedClosed
        }
        Then("a row of another namespace is a programming error") {
            val other = VanCancelEntry.closure(first.namespace.copy(institutionAccount = "SIMMERCH0002"), ids.closureId, now)
            shouldThrow<IllegalArgumentException> { cancel(existing = other) }
        }
    }

    Given("requests the VAN refuses without a record") {
        Then("an environment other than TEST, or a request ID older than the cancel inquiry retention, is refused") {
            cancel(request = request(environment = "LIVE")) shouldBe VanCancelJudgement.Refused
            val old = VanCancelFixture.cancelRequestId(age = profileA.cancel.inquiryRetention.plusSeconds(1))
            cancel(request = request(requestId = old)) shouldBe VanCancelJudgement.Refused
        }
    }

    Given("the cancel request value") {
        Then("its payload hash ignores nothing of the business fields and starts with the sim-van-2 canonical prefix") {
            val r = request()
            r.payloadHash() shouldBe request().payloadHash()
            listOf(
                r.copy(merchantRef = "mid-demo-0002"), r.copy(routeRef = "SIM-VAN-02"), r.copy(originalRequestId = VanFixture.requestId(seq = 9)),
                r.copy(originalTransactionDate = LocalDate.parse("2026-10-14")), r.copy(cancelAmountMinor = 9_999), r.copy(scope = VanCancelScope.PARTIAL),
            ).forEach { changed -> (changed.payloadHash() == r.payloadHash()) shouldBe false }
        }
        Then("a non-positive amount or a multi-line field is rejected") {
            shouldThrow<IllegalArgumentException> { request(amountMinor = 0) }
            shouldThrow<IllegalArgumentException> { request(originalApprovalNumber = "3000\n0001") }
        }
    }

    Given("the cancel ledger entry shape") {
        Then("APPROVED needs its terms and sequence, DECLINED needs a category and nothing else, a closure carries no decision") {
            val ns = VanNamespace(VanFixture.CLIENT, "TEST", "SIMMERCH0001", VanNamespace.CANCEL, VanCancelFixture.cancelRequestId())
            val terms = VanCancelTerms("40000001", "SIMPTX-C1", "SIMPTX-0000000001", 1_000, "KRW", VanDates.seoulDate(now), VanDates.seoulDate(now))
            shouldThrow<IllegalArgumentException> {
                VanCancelEntry.reconstitute("vd_x", ns, VanEntryKind.DECISION, "h", "ar_x", null, VanDecisionOutcome.APPROVED, terms, null, null, now)
            }
            shouldThrow<IllegalArgumentException> {
                VanCancelEntry.reconstitute("vd_x", ns, VanEntryKind.DECISION, "h", "ar_x", null, VanDecisionOutcome.DECLINED, null, 1, VanCancelDeclineCategory.ISSUER_DECLINED, now)
            }
            shouldThrow<IllegalArgumentException> {
                VanCancelEntry.reconstitute("vc_x", ns, VanEntryKind.CLOSURE, null, "ar_x", null, null, null, null, null, now)
            }
            shouldThrow<IllegalArgumentException> {
                VanCancelEntry.reconstitute("vd_x", ns.copy(operation = VanNamespace.AUTHORIZE), VanEntryKind.DECISION, "h", "ar_x", null, VanDecisionOutcome.APPROVED, terms, 1, null, now)
            }
            terms.copy(cancelApprovalNumber = null).cancelApprovalNumber shouldBe null // n-5: 기관이 취소 승인번호를 주지 않을 수 있다
        }
    }
})

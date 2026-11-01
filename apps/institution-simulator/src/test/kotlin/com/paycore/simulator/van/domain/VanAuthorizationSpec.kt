package com.paycore.simulator.van.domain

import com.paycore.simulator.van.domain.VanFixture.authorize
import com.paycore.simulator.van.domain.VanFixture.evidence
import com.paycore.simulator.van.domain.VanFixture.now
import com.paycore.simulator.van.domain.VanFixture.request
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * 가상 VAN 승인 판정(AUTH-001 §5.3 판정 순서 — 앞 단계가 결정하면 멈춤). 기대값은 명세 표에서 직접 적는다. 원장 INSERT·응답 송신 순서·동시성은 Simulator 실DB 시험
 * (VanServerSpec)의 몫이다.
 */
class VanAuthorizationSpec : BehaviorSpec({

    fun approved(j: VanJudgement): VanLedgerEntry {
        val d = j.shouldBeInstanceOf<VanJudgement.Decide>()
        d.entry.outcome shouldBe VanDecisionOutcome.APPROVED
        return d.entry
    }

    fun declined(j: VanJudgement): VanDeclineCategory? {
        val d = j.shouldBeInstanceOf<VanJudgement.Decide>()
        d.entry.outcome shouldBe VanDecisionOutcome.DECLINED
        d.entry.approval shouldBe null
        return d.entry.declineCategory
    }

    Given("the payload hash of an authorization request (rev 0.3 m-7)") {
        Then("it covers every business field") {
            val base = request()
            listOf(
                base.copy(amountMinor = 10_001), base.copy(currency = "USD"), base.copy(plan = VanPlan.installment(3)), base.copy(merchantRef = "mid-demo-0002"),
                base.copy(institutionAccount = "SIMMERCH0002"), base.copy(routeRef = "SIMVAN02"), base.copy(requestId = VanFixture.requestId(seq = 2)),
                base.copy(authentication = base.authentication.copy(evidenceRef = "sim-ev-B")), base.copy(authentication = base.authentication.copy(issuerCode = "SIMISSB")),
            ).forEach { it.payloadHash() shouldNotBe base.payloadHash() }
            request().payloadHash() shouldBe base.payloadHash()
        }
    }

    Given("an existing ledger row for the same namespace (§5.3-1)") {
        val first = approved(authorize())
        Then("the same payload hash replays the existing decision without a new one") {
            authorize(existing = first).shouldBeInstanceOf<VanJudgement.Replay>().entry shouldBe first
        }
        Then("a different payload is REQUEST_CONFLICT and the ledger is unchanged") {
            authorize(request = request(amountMinor = 9_000), existing = first) shouldBe VanJudgement.Conflict
        }
        Then("a no-effect closure tombstone rejects the late original request (REJECTED_CLOSED)") {
            val closure = VanLedgerEntry.closure(VanNamespace.of(VanFixture.CLIENT, request()), VanFixture.ids.closureId, null, now)
            authorize(existing = closure) shouldBe VanJudgement.RejectedClosed
        }
    }

    Given("a PENDING reservation for the same namespace (rev 0.3 H-3)") {
        // 예약은 확정되면 상태가 바뀐다 — 행마다 새 예약을 만든다.
        fun hold() = authorize(request = request(), evidence = evidence(cardRef = "VC-VAN-PENDING-THEN-APPROVE"))
            .shouldBeInstanceOf<VanJudgement.Hold>().pending
        Then("before its resolve time the same request stays PENDING with the same pendingId") {
            authorize(pending = hold()).shouldBeInstanceOf<VanJudgement.StillPending>().pending.pendingId shouldBe VanFixture.ids.pendingId
        }
        Then("after its resolve time the reservation resolves into an APPROVED decision") {
            val h = hold()
            val resolved = authorize(pending = h, at = h.resolveAfter).shouldBeInstanceOf<VanJudgement.ResolvePending>()
            resolved.entry.outcome shouldBe VanDecisionOutcome.APPROVED
            resolved.entry.namespace shouldBe h.namespace
            h.state shouldBe VanPendingState.RESOLVED
        }
        Then("a different payload conflicts") {
            authorize(request = request(amountMinor = 1), pending = hold()) shouldBe VanJudgement.Conflict
        }
    }

    Given("a request outside TEST (§5.3-2, §5.6 layer 4)") {
        Then("it is refused without a ledger record") {
            authorize(request = request(environment = "LIVE")) shouldBe VanJudgement.Refused
        }
    }

    Given("a new request whose request ID is older than the inquiry retention (review #2 n-3, Demo rule)") {
        Then("it is refused without a ledger record; a recorded decision of such an ID still replays") {
            val old = request(requestId = VanFixture.requestId(age = VanFixture.profileA.inquiryRetention.plusSeconds(1)))
            authorize(request = old) shouldBe VanJudgement.Refused
            val decided = authorize(request = old, at = now.minus(VanFixture.profileA.inquiryRetention)).shouldBeInstanceOf<VanJudgement.Decide>().entry
            authorize(request = old, existing = decided).shouldBeInstanceOf<VanJudgement.Replay>()
        }
    }

    Given("the authentication evidence re-validation (§5.3-3, D-AUTH1-004)") {
        Then("an evidence whose validUntil has passed is an institution-confirmed DECLINED AUTHENTICATION_INVALID (D-AUTH1-004)") {
            declined(authorize(evidence = evidence(validUntil = now))) shouldBe VanDeclineCategory.AUTHENTICATION_INVALID
            declined(authorize(evidence = evidence(validUntil = now.minusSeconds(1)))) shouldBe VanDeclineCategory.AUTHENTICATION_INVALID
        }
        Then("missing, other client's, not authenticated, binding mismatch or reused by another request ID → DECLINED AUTHENTICATION_INVALID") {
            listOf(
                authorize(evidence = null),
                authorize(evidence = evidence(clientId = "pg-other")),
                authorize(evidence = evidence(authenticated = false)),
                authorize(evidence = evidence(amountMinor = 9_999)),
                authorize(evidence = evidence(plan = VanPlan.installment(3))),
                authorize(request = request(merchantRef = "mid-demo-0002"), evidence = evidence(request = request())),
                authorize(request = request(issuerCode = "SIMISSB"), evidence = evidence(request = request())),
                authorize(evidenceUsedByAnotherRequest = true),
            ).forEach { declined(it) shouldBe VanDeclineCategory.AUTHENTICATION_INVALID }
        }
        Then("an AUTHENTICATION_INVALID decline does not consume the evidence; a decision after a valid evidence does") {
            authorize(evidence = null).shouldBeInstanceOf<VanJudgement.Decide>().consumesEvidence shouldBe false
            authorize().shouldBeInstanceOf<VanJudgement.Decide>().consumesEvidence shouldBe true
        }
    }

    Given("the plan re-validation (§5.3-4, D-AUTH1-003)") {
        Then("a check card installment is DECLINED PLAN_NOT_SUPPORTED") {
            val r = request(plan = VanPlan.installment(3))
            declined(authorize(request = r, evidence = evidence(request = r, cardRef = "VC-CHECK-OK", cardType = "CHECK"))) shouldBe VanDeclineCategory.PLAN_NOT_SUPPORTED
        }
        Then("installment months outside the profile are DECLINED PLAN_NOT_SUPPORTED; months in the profile are approved") {
            val outside = request(plan = VanPlan.installment(5))
            declined(authorize(request = outside, evidence = evidence(request = outside))) shouldBe VanDeclineCategory.PLAN_NOT_SUPPORTED
            val inside = request(plan = VanPlan.installment(12))
            approved(authorize(request = inside, evidence = evidence(request = inside))).approval!!.plan shouldBe VanPlan.installment(12)
        }
        Then("a check card lump sum is approved with card type CHECK") {
            approved(authorize(evidence = evidence(cardRef = "VC-CHECK-OK", cardType = "CHECK"))).approval!!.cardType shouldBe "CHECK"
        }
    }

    Given("the virtual card VAN scenarios (§5.3 Demo table)") {
        Then("a normal card is approved with the requested terms, the evidence card type and the decision date in Asia/Seoul") {
            val a = approved(authorize()).approval!!
            a.amountMinor shouldBe 10_000
            a.currency shouldBe "KRW"
            a.plan shouldBe VanPlan.LUMP_SUM
            a.cardType shouldBe "CREDIT"
            a.approvalNumber shouldBe "30000001"
            a.providerTransactionId shouldBe "SIMPTX-0000000001"
            a.institutionTransactionDate shouldBe LocalDate.of(2026, 10, 16)
            a.institutionBusinessDate shouldBe LocalDate.of(2026, 10, 16)
        }
        Then("VC-VAN-DECLINE → DECLINED ISSUER_DECLINED") {
            declined(authorize(evidence = evidence(cardRef = "VC-VAN-DECLINE"))) shouldBe VanDeclineCategory.ISSUER_DECLINED
        }
        Then("partial approval, card type mismatch and installment months mismatch change the approved terms") {
            approved(authorize(evidence = evidence(cardRef = "VC-VAN-PARTIAL-APPROVAL"))).approval!!.amountMinor shouldBe 9_999
            approved(authorize(evidence = evidence(cardRef = "VC-VAN-CARDTYPE-MISMATCH"))).approval!!.cardType shouldBe "CHECK"
            val r = request(plan = VanPlan.installment(3))
            approved(authorize(request = r, evidence = evidence(request = r, cardRef = "VC-VAN-MONTHS-MISMATCH"))).approval!!.plan shouldBe VanPlan.installment(6)
        }
        Then("VC-VAN-DROP-BEFORE-RECORD drops the request without a decision") {
            authorize(evidence = evidence(cardRef = "VC-VAN-DROP-BEFORE-RECORD")) shouldBe VanJudgement.DropWithoutRecord
        }
        Then("transport scenarios record a normal decision and carry their behaviour to the transport") {
            listOf(
                "VC-VAN-LATE-RESPONSE" to VanBehaviour.LATE_RESPONSE, "VC-VAN-DROP-AFTER-APPROVE" to VanBehaviour.DROP_AFTER_APPROVE,
                "VC-VAN-DUPLICATE-RESPONSE" to VanBehaviour.DUPLICATE_RESPONSE, "VC-VAN-FRAGMENTED" to VanBehaviour.FRAGMENTED, "VC-VAN-BAD-MAC" to VanBehaviour.BAD_MAC,
            ).forEach { (card, behaviour) ->
                val d = authorize(evidence = evidence(cardRef = card)).shouldBeInstanceOf<VanJudgement.Decide>()
                d.entry.outcome shouldBe VanDecisionOutcome.APPROVED
                d.behaviour shouldBe behaviour
            }
        }
        Then("VC-VAN-PENDING-THEN-APPROVE holds a PENDING reservation resolving after the profile delay") {
            val h = authorize(evidence = evidence(cardRef = "VC-VAN-PENDING-THEN-APPROVE")).shouldBeInstanceOf<VanJudgement.Hold>()
            h.pending.resolveAfter shouldBe now.plus(VanFixture.profileA.pendingDelay)
            h.pending.state shouldBe VanPendingState.PENDING
        }
    }
})

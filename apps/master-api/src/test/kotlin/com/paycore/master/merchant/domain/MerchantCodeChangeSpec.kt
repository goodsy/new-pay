package com.paycore.master.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * MERCHANT-CODE-001 §8 MC-1b-1(§3.7.3·§3.7.4 — D-MCODE-002 Q-MC-7·Q-MC-10): [Merchant.changeCodeWithEvidence] 판정 순서 7 → 8 → 9 → 10 → 11(→ 자기 코드 TAKEN)과 seq 2 배정.
 * 결정 고정(D-MCODE-002 Q-MC-10): 원 가맹점 등록자 = 승인자여도 성공하고, 등록자 계열 새 거절 값이 없다(enum 집합 단언).
 */
class MerchantCodeChangeSpec : BehaviorSpec({

    val newCode = MerchantCode("shopabcd99")
    fun change(
        s: MerchantFixtures.Scenario, to: MerchantCode = newCode, expected: MerchantCode = s.merchant.currentCode, approver: ActorRef = MerchantFixtures.APPROVER,
    ) = s.merchant.changeCodeWithEvidence(to, expected, s.contractApproved, MerchantCodeChangeReason.TYPO_CORRECTION, MerchantFixtures.APPROVAL_ID, MerchantFixtures.changedAt, approver)
    fun changeAgain(m: Merchant, to: MerchantCode, contractApproved: Boolean = false) =
        m.changeCodeWithEvidence(to, m.currentCode, contractApproved, MerchantCodeChangeReason.OTHER_POLICY, MerchantFixtures.APPROVAL_ID, MerchantFixtures.changedAt, MerchantFixtures.APPROVER)
    fun rejection(block: () -> Unit): MasterRejection = shouldThrow<MasterRuleViolation> { block() }.rejection

    Given("a merchant before its first contract approval, never changed, ACTIVE") {
        Then("the change appends seq 2 CHANGE (replaces = old code, approval, reason, locked-now time, approver) and the current code becomes the new code") {
            val s = MerchantFixtures.beforeContractApproval()
            val old = s.merchant.currentCode
            val a = change(s)
            listOf(a.seq, a.code, a.replacesCode, a.source, a.approvalId, a.reasonCode, a.assignedAt, a.assignedBy) shouldBe listOf(
                2, newCode, old, MerchantCodeSource.CHANGE, MerchantFixtures.APPROVAL_ID, "TYPO_CORRECTION", MerchantFixtures.changedAt, MerchantFixtures.APPROVER,
            )
            s.merchant.currentCode shouldBe newCode
            s.merchant.codeAssignments.map { it.seq to it.code } shouldBe listOf(1 to old, 2 to newCode)
            MerchantCodeFixtures.retiredOf(s.merchant) shouldBe old
        }
    }

    Given("the judgement order 7 → 8 → 9 → 10 → 11 (fixed table — the first failing rule wins)") {
        Then("already changed AND contract approved → ALREADY_CHANGED (8 before 9); approved + mismatch → LOCKED (9 before 10); mismatch + reserved → MISMATCH (10 before 11)") {
            rejection { changeAgain(MerchantFixtures.alreadyChanged(), MerchantCode("shopzzzz01"), contractApproved = true) } shouldBe MasterRejection.MERCHANT_CODE_ALREADY_CHANGED
            rejection { change(MerchantFixtures.afterContractApproval(), expected = MerchantCode("otherabc12")) } shouldBe MasterRejection.MERCHANT_CODE_LOCKED
            rejection { change(MerchantFixtures.beforeContractApproval(), to = MerchantCode("adminshop1"), expected = MerchantCode("otherabc12")) } shouldBe
                MasterRejection.MERCHANT_CODE_MISMATCH
        }
        Then("each rule alone: reserved new code → RESERVED, expectedCurrent ≠ current → MISMATCH, own current code → TAKEN, contract approved → LOCKED") {
            listOf(
                rejection { change(MerchantFixtures.beforeContractApproval(), to = MerchantCode("paycoreshop")) },
                rejection { change(MerchantFixtures.beforeContractApproval(), expected = MerchantCode("otherabc12")) },
                rejection { change(MerchantFixtures.beforeContractApproval(), to = MerchantCode(MerchantCodeFixtures.VALID)) },
                rejection { change(MerchantFixtures.afterContractApproval()) },
            ) shouldBe listOf(
                MasterRejection.MERCHANT_CODE_RESERVED, MasterRejection.MERCHANT_CODE_MISMATCH, MasterRejection.MERCHANT_CODE_TAKEN, MasterRejection.MERCHANT_CODE_LOCKED,
            )
        }
        Then("a rejected change leaves the assignments untouched (no partial append)") {
            val s = MerchantFixtures.afterContractApproval()
            rejection { change(s) }
            s.merchant.codeAssignments.size shouldBe 1
        }
        Then("MC-1b-5 (Domain half): the same merchant changing back to its retired code is ALREADY_CHANGED (the one change is spent)") {
            val changed = MerchantFixtures.alreadyChanged()
            rejection { changeAgain(changed, MerchantCodeFixtures.retiredOf(changed)) } shouldBe MasterRejection.MERCHANT_CODE_ALREADY_CHANGED
        }
    }

    Given("D-MCODE-002 Q-MC-10 decision pinning (requester ≠ approver only — the original merchant registrant may approve)") {
        Then("MerchantFixtures.registeredBy(B) changed by approver B succeeds (seq 2 assigned_by = B)") {
            val b = MerchantFixtures.REGISTRANT
            val s = MerchantFixtures.registeredBy(b)
            s.merchant.createdBy shouldBe b
            change(s, approver = b).assignedBy shouldBe b
        }
        Then("MasterRejection has no registrant rejection for the code change (APPROVER_IS_MERCHANT_REGISTRANT absent — the registrant family stays the three existing values)") {
            MasterRejection.entries.map { it.name }.filter { it.contains("REGISTRANT") }.toSet() shouldBe
                setOf("APPROVER_IS_REVISION_REGISTRANT", "APPROVER_IS_ACTIVATION_REGISTRANT", "APPROVER_IS_SITE_REGISTRANT")
            MasterRejection.entries.map { it.name }.filter { it.startsWith("MERCHANT_CODE_") }.toSet() shouldBe setOf(
                "MERCHANT_CODE_RESERVED", "MERCHANT_CODE_TAKEN", "MERCHANT_CODE_ALREADY_CHANGED", "MERCHANT_CODE_LOCKED", "MERCHANT_CODE_MISMATCH",
            )
        }
    }

    Given("the fixed reason list (C-12 — Demo values)") {
        Then("exactly TYPO_CORRECTION, MERCHANT_REQUEST, OTHER_POLICY") {
            MerchantCodeChangeReason.entries.map { it.name } shouldBe listOf("TYPO_CORRECTION", "MERCHANT_REQUEST", "OTHER_POLICY")
        }
    }
})

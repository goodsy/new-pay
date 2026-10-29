package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 승인 대상(§4.9 — sealed `ApprovalTarget`): 계정 대상 `AdminAccount(actorId, securityVersion)`, Payment 대상 `Payment(paymentId, balanceRevision)`.
 * 대상 버전은 같은 `target_security_version` 열(I-13). 2인 규칙(§4.9 `ApprovalParticipants`): 요청자 역할 = `kind.requesterRole`, Payment 대상은 요청자 ≠ 승인자만
 * (대상이 사람이 아니다), 계정 대상은 요청자·승인자·대상 모두 다름.
 */
class ApprovalTargetSpec : BehaviorSpec({

    val now = Instant.parse("2026-10-25T01:00:00.000Z")
    val requester = "adm_" + "1".repeat(26)
    val approver = "adm_" + "2".repeat(26)

    Given("an approval target") {
        Then("each target has its kind, reference and version") {
            listOf(ApprovalTarget.AdminAccount(approver, 7), ApprovalTarget.Payment(OwnerServiceApprovalFixture.PAYMENT_ID, 3)).map { Triple(it.kind, it.ref, it.version) } shouldBe
                listOf(
                    Triple(ApprovalTargetKind.ADMIN_ACCOUNT, approver, 7L),
                    Triple(ApprovalTargetKind.PAYMENT, OwnerServiceApprovalFixture.PAYMENT_ID, 3L),
                )
        }
        Then("a Payment target needs a pay_ + ULID id and a non-negative balance revision (0 and Long.MAX_VALUE are valid)") {
            listOf(0L, Long.MAX_VALUE).map { ApprovalTarget.Payment(OwnerServiceApprovalFixture.PAYMENT_ID, it).version } shouldBe listOf(0L, Long.MAX_VALUE)
            listOf("pay_123", "pcn_01JC0000000000000000000001", "pay_01JC000000000000000000000I", " pay_01JC0000000000000000000001", "").forEach { bad ->
                shouldThrow<IllegalArgumentException> { ApprovalTarget.Payment(bad, 0) }
            }
            shouldThrow<IllegalArgumentException> { ApprovalTarget.Payment(OwnerServiceApprovalFixture.PAYMENT_ID, -1) }
        }
        Then("an account target needs a non-blank actor id") {
            shouldThrow<IllegalArgumentException> { ApprovalTarget.AdminAccount("", 0) }
        }
    }

    Given("an approval request with a target") {
        Then("the request keeps its target; account accessors serve only account targets") {
            val payment = OwnerServiceApprovalFixture.pending("apr_" + "0".repeat(25) + "1", requester, now)
            payment.target shouldBe OwnerServiceApprovalFixture.PAYMENT_TARGET
            val account = ApprovalRequest.create("apr_" + "0".repeat(25) + "2", ApprovalKind.ACCOUNT_UNLOCK, approver, 9, "{}", null, null, requester, "R", now, OwnerServiceApprovalFixture.VALIDITY)
            listOf(account.target, account.targetActorId, account.targetSecurityVersion) shouldBe listOf(ApprovalTarget.AdminAccount(approver, 9), approver, 9L)
        }
        Then("the operator requesting a Payment approval is not the target (the target is not a person)") {
            OwnerServiceApprovalFixture.pending("apr_" + "0".repeat(25) + "3", requester, now).requestedBy shouldBe requester
        }
    }

    Given("the 2-person rule (ApprovalParticipants)") {
        Then("the requester role comes from the kind; the approver role is APPROVER") {
            ApprovalKind.entries.associateWith { ApprovalParticipants.requesterRole(it) } shouldBe ApprovalKind.entries.associateWith { it.requesterRole }
            ApprovalParticipants.requesterRole(ApprovalKind.OPERATOR_CORRECTION_CANCEL) shouldBe "OPERATOR"
            ApprovalParticipants.APPROVER_ROLE shouldBe "APPROVER"
        }
        Then("Payment target: requester ≠ approver only; account target: requester, approver and target all differ") {
            val payment = ApprovalTarget.Payment(OwnerServiceApprovalFixture.PAYMENT_ID, 1)
            val account = ApprovalTarget.AdminAccount("adm_" + "3".repeat(26), 1)
            listOf(
                ApprovalParticipants.distinct(payment, requester, approver),
                ApprovalParticipants.distinct(payment, requester, requester),
                ApprovalParticipants.distinct(account, requester, approver),
                ApprovalParticipants.distinct(account, requester, account.actorId),
                ApprovalParticipants.distinct(account, account.actorId, approver),
            ) shouldBe listOf(true, false, true, false, false)
        }
    }
})

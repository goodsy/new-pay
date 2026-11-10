package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant

/**
 * 가맹점 계약 승인 종류의 Domain 규칙(MERCHANT-OPS-002 B2-2 §9.5 — D-MOPS-004·007·008, C-11·C-16·C-20·C-23·C-30). 명시 Fixture: 가맹점 `mer_01JCME…01`, 계약 `ctr_01JCCT…CT1`
 * Revision 2(MERCHANT Scope, 2026-11-11 00:00 KST 시작, 종료 없음, 승인 전), 등록자 `admin:adm_01JCRG…RG1`.
 */
class MerchantContractApprovalDomainSpec : BehaviorSpec({

    val merchantId = "mer_01JCME00000000000000000001"
    val contractId = "ctr_01JCCT00000000000000000CT1"
    val now = Instant.parse("2026-11-10T09:00:00.000Z")
    val approvalId = "apr_01JC0000000000000000000M01"
    fun pinned(name: String = "Demo \"Shop\" \\ 1", registeredBy: String = "admin:adm_01JCRG0000000000000000RG01") = PinnedContractApprovalTarget(
        merchantId = merchantId, merchantName = name, contractId = contractId, revisionNo = 2, scopeKind = "MERCHANT", scopeSiteIds = emptyList(),
        effectiveFrom = Instant.parse("2026-11-10T15:00:00.000Z"), effectiveTo = null, registeredBy = registeredBy,
    )

    Given("MD-1 the new kind MERCHANT_CONTRACT_APPROVAL (owner-service, OPERATOR → APPROVER, target MERCHANT — C-11)") {
        Then("2-TX, requested by OPERATOR, approved by APPROVER, never a privileged role") {
            val kind = ApprovalKind.MERCHANT_CONTRACT_APPROVAL
            listOf(kind.executionModel, kind.requesterRole, kind.approverRole, kind.targetKind, kind.mayActivatePrivilegedRole(null), kind.escalatesPrivilege) shouldBe
                listOf(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", "APPROVER", ApprovalTargetKind.MERCHANT, false, false)
        }
        Then("the target is the merchantId (mer_ + ULID), version 0 (C-16 — pinned comparison + MASTER re-check); requester ≠ approver") {
            val target = ApprovalTarget.Merchant(merchantId)
            listOf(target.kind, target.ref, target.version) shouldBe listOf(ApprovalTargetKind.MERCHANT, merchantId, 0L)
            shouldThrow<IllegalArgumentException> { ApprovalTarget.Merchant("mer_01jcme00000000000000000001") }
            shouldThrow<IllegalArgumentException> { ApprovalTarget.Merchant(contractId) }
            ApprovalParticipants.distinct(target, "adm_a", "adm_b") shouldBe true
            ApprovalParticipants.distinct(target, "adm_a", "adm_a") shouldBe false
        }
        Then("PENDING → EXECUTING → EXECUTED / OWNER_REJECTED; the self-approval stays refused by the shared decision table") {
            val request = ApprovalRequest.create(
                approvalId, ApprovalKind.MERCHANT_CONTRACT_APPROVAL, ApprovalTarget.Merchant(merchantId), pinned().toSnapshotJson(), null, null, "adm_req", "Req", now,
                Duration.ofHours(24),
            )
            request.decideApproval("adm_req", now) shouldBe ApprovalDecision.Refused("SELF_APPROVAL")
            val executing = request.beginExecution("adm_apv", "Apv", now.plusSeconds(1))
            listOf(executing.status, executing.executed().status, executing.ownerRejected("APPROVAL_EXPIRED").status) shouldBe
                listOf(ApprovalStatus.EXECUTING, ApprovalStatus.EXECUTED, ApprovalStatus.OWNER_REJECTED)
            shouldThrow<IllegalArgumentException> {
                ApprovalRequest.create(approvalId, ApprovalKind.MERCHANT_CONTRACT_APPROVAL, ApprovalTarget.Payment("pay_01JC0000000000000000000A01", 0), "{}", null, null, "a", "A", now, Duration.ofHours(1))
            }
        }
    }

    Given("MD-2 the owner execution row (mop1 fingerprint, MASTER reference ctr_…#n — C-30 INT range)") {
        val start = OwnerExecution.start(
            ApprovalKind.MERCHANT_CONTRACT_APPROVAL, approvalId, "{}".toByteArray(), "mop1:" + "a".repeat(64), now, now.plusSeconds(3600), "adm_apv", now.plusSeconds(42),
        )
        Then("mop1 is the kind's prefix; references are ctr_<ULID>#1..2147483647 only") {
            OwnerExecution.fingerprintPrefix(ApprovalKind.MERCHANT_CONTRACT_APPROVAL) shouldBe "mop1"
            start.conformsTo(ApprovalKind.MERCHANT_CONTRACT_APPROVAL) shouldBe true
            start.conformsTo(ApprovalKind.ACQ_REACQUISITION) shouldBe false
            listOf("$contractId#1", "$contractId#2147483647", "$contractId#0", "$contractId#2147483648", "$contractId#01", contractId, "pcn_01JC0000000000000000000001")
                .map { OwnerExecution.referenceFits(ApprovalKind.MERCHANT_CONTRACT_APPROVAL, it) } shouldBe listOf(true, true, false, false, false, false, false)
            OwnerExecution.referenceFits(ApprovalKind.OPERATOR_CORRECTION_CANCEL, "$contractId#1") shouldBe false
            start.ownerResult(now, null, "$contractId#2").ownerReference shouldBe "$contractId#2"
            shouldThrow<IllegalArgumentException> {
                OwnerExecution.start(ApprovalKind.MERCHANT_CONTRACT_APPROVAL, approvalId, "{}".toByteArray(), "acd1:" + "a".repeat(64), now, now.plusSeconds(60), "x", now)
            }
        }
    }

    Given("MD-3 the pinned target (display + comparison value, registeredBy — D-MOPS-008 ③) and the request input rules") {
        Then("the snapshot JSON is deterministic, escapes the free merchant name and has no registration number or channel identifier") {
            pinned().toSnapshotJson() shouldBe
                """{"merchantId":"$merchantId","merchantName":"Demo \"Shop\" \\ 1","contractId":"$contractId","revisionNo":2,"scopeKind":"MERCHANT","scopeSiteIds":[],""" +
                """"effectiveFrom":"2026-11-10T15:00:00.000Z","effectiveTo":null,"approved":false,"registeredBy":"admin:adm_01JCRG0000000000000000RG01"}"""
            shouldThrow<IllegalArgumentException> { pinned(name = "line\nbreak") }
            shouldThrow<IllegalArgumentException> { pinned(registeredBy = "") }
        }
        Then("comparison: a different registeredBy, approval or period is a changed target; the display name is not compared") {
            val p = pinned()
            p.sameTargetAs(p.copy(merchantName = "Renamed")) shouldBe true
            listOf(p.copy(registeredBy = "admin:adm_X"), p.copy(effectiveTo = Instant.parse("2026-12-01T15:00:00.000Z")), p.copy(scopeKind = "SITES")).map { p.sameTargetAs(it) } shouldBe listOf(false, false, false)
        }
        Then("evidenceRef is required in the reacquisition grammar (no trimming, no card-number look-alike); ids and revision in range") {
            fun problem(evidence: String, merchant: String = merchantId, contract: String = contractId, revision: Long = 2) =
                MerchantOpsInputRules.contractApprovalProblem(merchant, contract, revision, evidence)
            listOf(
                problem("CONTRACT-2026/0001#p1"), problem(""), problem(" CONTRACT-1"), problem("x".repeat(129)), problem("x".repeat(130)), problem("4111111111111111"),
                problem("E-1", merchant = "mer_x"), problem("E-1", contract = "ctr_x"), problem("E-1", revision = 0), problem("E-1", revision = 2_147_483_648),
            ) shouldBe listOf(
                null, "evidenceRef" to "FORMAT", "evidenceRef" to "FORMAT", "evidenceRef" to "FORMAT", "evidenceRef" to "FORMAT", "evidenceRef" to "CARD_NUMBER_PATTERN",
                "merchantId" to "FORMAT", "contractId" to "FORMAT", "revisionNo" to "RANGE", "revisionNo" to "RANGE",
            )
        }
        Then("the command snapshot holds the evidence (INSERT-only) and never prints it") {
            val c = MerchantOpsRequestSnapshot(approvalId, MerchantOpsCommandKind.CONTRACT_APPROVAL, merchantId, pinned(), "CONTRACT-2026/0001#p1", now)
            c.toString().contains("CONTRACT-2026") shouldBe false
            shouldThrow<IllegalArgumentException> { c.copy(merchantId = "mer_01JCME00000000000000000002") }
            shouldThrow<IllegalArgumentException> { c.copy(evidenceRef = "bad ref") }
        }
        Then("OWNER_UNRESOLVABLE is a reconciliation finding kind (C-28) — after the existing six") {
            ReconciliationFindingKind.entries.last() shouldBe ReconciliationFindingKind.OWNER_UNRESOLVABLE
        }
    }
})

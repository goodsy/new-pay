package com.paycore.admin.approval.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 첫 Default MID 발급 승인 종류의 Domain 규칙(MERCHANT-OPS-002 B2-3 §9.5 — C-11·C-20). 명시 Fixture: 가맹점 `mer_…01`, 계약 `ctr_…CT1` Revision 1(승인됨, 2026-11-10 00:00 KST 시작),
 * CSA `csa_…CS1`, PRIMARY Site `site_…ST1`(WEB).
 */
class MerchantFirstMidDomainSpec : BehaviorSpec({

    val merchantId = "mer_01JCME00000000000000000001"
    val now = Instant.parse("2026-11-10T09:00:00.000Z")
    fun pinned(name: String = "Demo \"Shop\"") = PinnedFirstMidTarget(
        merchantId = merchantId, merchantName = name, contractId = "ctr_01JCCT00000000000000000CT1", agreementId = "csa_01JCCS00000000000000000CS1", revisionNo = 1,
        effectiveFrom = Instant.parse("2026-11-09T15:00:00.000Z"), effectiveTo = null, primarySiteId = "site_01JCST00000000000000000ST1", channelType = "WEB",
    )

    Given("FD-1 the kind MERCHANT_FIRST_MID_ISSUANCE (owner-service, OPERATOR → APPROVER, target MERCHANT — same unresolved key as the contract approval, C-20)") {
        Then("2-TX, OPERATOR, APPROVER, no privileged role; mop1 prefix; references are mid_<ULID> only") {
            val kind = ApprovalKind.MERCHANT_FIRST_MID_ISSUANCE
            listOf(kind.executionModel, kind.requesterRole, kind.approverRole, kind.targetKind, kind.mayActivatePrivilegedRole(null)) shouldBe
                listOf(ApprovalExecutionModel.OWNER_SERVICE, "OPERATOR", "APPROVER", ApprovalTargetKind.MERCHANT, false)
            OwnerExecution.fingerprintPrefix(kind) shouldBe "mop1"
            listOf("mid_01JCMD00000000000000000001", "ctr_01JCCT00000000000000000CT1#1", "mid_x").map { OwnerExecution.referenceFits(kind, it) } shouldBe listOf(true, false, false)
            OwnerExecution.referenceFits(ApprovalKind.MERCHANT_CONTRACT_APPROVAL, "mid_01JCMD00000000000000000001") shouldBe false
            OwnerExecution.start(kind, "apr_01JC0000000000000000000M02", "{}".toByteArray(), "mop1:" + "b".repeat(64), now, now.plusSeconds(60), "adm_a", now)
                .ownerResult(now, null, "mid_01JCMD00000000000000000001").ownerReference shouldBe "mid_01JCMD00000000000000000001"
        }
    }

    Given("FD-2 the pinned first-MID target (§9.5 — accountCount 0, approved revision, CSA, PRIMARY Site; no registration number or channel identifier)") {
        Then("deterministic JSON in the specified field order; the free name is escaped; comparison ignores the display name") {
            pinned().toSnapshotJson() shouldBe
                """{"merchantId":"$merchantId","merchantName":"Demo \"Shop\"","accountCount":0,"contractId":"ctr_01JCCT00000000000000000CT1",""" +
                """"agreementId":"csa_01JCCS00000000000000000CS1","revisionNo":1,"approved":true,"effectiveFrom":"2026-11-09T15:00:00.000Z","effectiveTo":null,""" +
                """"primarySiteId":"site_01JCST00000000000000000ST1","channelType":"WEB"}"""
            pinned().sameTargetAs(pinned(name = "Renamed")) shouldBe true
            pinned().sameTargetAs(pinned().copy(primarySiteId = "site_01JCST00000000000000000ST2")) shouldBe false
            pinned().sameTargetAs(pinned().copy(accountCount = 1)) shouldBe false
            shouldThrow<IllegalArgumentException> { pinned().copy(agreementId = "csa_x") }
        }
        Then("input rules: merchant/contract/CSA/Site formats, revision range, evidence required (same grammar as the contract approval)") {
            fun problem(evidence: String = "MID-2026/0001#p1", agreement: String = "csa_01JCCS00000000000000000CS1", site: String = "site_01JCST00000000000000000ST1", revision: Long = 1) =
                MerchantOpsInputRules.firstMidProblem(merchantId, "ctr_01JCCT00000000000000000CT1", agreement, revision, site, evidence)
            listOf(problem(), problem(evidence = ""), problem(agreement = "csa_x"), problem(site = "site_x"), problem(revision = 0)) shouldBe listOf(
                null, "evidenceRef" to "FORMAT", "agreementId" to "FORMAT", "primarySiteId" to "FORMAT", "revisionNo" to "RANGE",
            )
        }
        Then("the command snapshot carries either pinned kind and matches the command kind") {
            val s = MerchantOpsRequestSnapshot("apr_01JC0000000000000000000M02", MerchantOpsCommandKind.FIRST_MID_ISSUANCE, merchantId, pinned(), "MID-1", now)
            s.toString().contains("MID-1") shouldBe false
            shouldThrow<IllegalArgumentException> { s.copy(commandKind = MerchantOpsCommandKind.CONTRACT_APPROVAL) }
        }
    }
})

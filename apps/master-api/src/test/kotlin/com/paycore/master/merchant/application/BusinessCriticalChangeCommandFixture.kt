package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovedBusinessCriticalChangeCommand
import com.paycore.master.merchant.application.provided.ProposeBusinessCriticalChangeCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.BusinessChangeDigest
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef

/**
 * BIZ-CRIT-APPROVAL-001 S2 Named Fixture(application·통합 시험, src/test 전용). 제안 요청자는 ADMIN 정규형 [REQUESTER](세션 OPERATOR — M-6 대조 대상), 근거는
 * [ApprovalEvidenceFixtures.distinctActors]에 같은 입력의 `mop1` 지문을 붙인다(ADMIN 계산과 같음). 무효 의도(요청자 불일치 등)는 인자로 명시한다.
 */
object BusinessCriticalChangeCommandFixture {
    val REQUESTER: ActorRef = ApprovalEvidenceFixtures.REQUESTER
    val OTHER_OPERATOR: ActorRef = ApprovalEvidenceFixtures.OTHER
    const val EVIDENCE = "BIZ-DOC/2026-0001#p1"
    const val REASON = "상호 변경 신고"

    @Suppress("LongParameterList")
    fun propose(
        businessId: String,
        version: Long,
        name: String,
        profile: BusinessProfile,
        type: BusinessType = BusinessType.CORPORATION,
        reason: String? = REASON,
        evidence: String = EVIDENCE,
        actor: ActorRef = REQUESTER,
        key: AdminIdempotencyKey = OnboardingFixture.key(),
    ) = ProposeBusinessCriticalChangeCommand(
        OnboardingFixture.request(key, actor), BusinessId(businessId), version, reason?.let(::BusinessChangeReason), EvidenceRef(evidence), type, DisplayName(name), profile,
    )

    /** 제안을 고정한 근거 경로 명령(요청자 = [requestedBy], 승인자 = APPROVER). */
    @Suppress("LongParameterList")
    fun apply(
        businessId: String,
        baseVersion: Long,
        proposalId: String,
        digest: String,
        requestedBy: ActorRef = REQUESTER,
        evidence: ApprovalEvidence = ApprovalEvidenceFixtures.distinctActors(requestedBy = requestedBy),
    ): ApprovedBusinessCriticalChangeCommand {
        val fields = listOf(businessId, baseVersion.toString(), proposalId, digest)
        val e = ApprovedCommandFixture.withFingerprint(evidence, AdminOperation.APPLY_BUSINESS_CRITICAL_CHANGE, fields)
        return ApprovedBusinessCriticalChangeCommand(
            ApprovedCommandFixture.requestOf(e), e, ApprovalEvidenceFixtures.DEMO_WINDOW, BusinessId(businessId), baseVersion, BusinessChangeProposalId(proposalId),
            BusinessChangeDigest(digest),
        )
    }
}

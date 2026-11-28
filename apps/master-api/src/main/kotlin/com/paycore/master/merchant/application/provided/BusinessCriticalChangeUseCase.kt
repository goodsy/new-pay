package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalWindow
import com.paycore.master.merchant.domain.BusinessChangeDigest
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessChangeReason
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessProfile
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EvidenceRef

/**
 * 사업자 중요정보 변경 2인 경로(BIZ-CRIT-APPROVAL-001 §3.3·§5.1 — S2). 두 명령 모두 MASTER 로컬 TX 하나([AdminCommandResult] 틀).
 *
 * - [propose]: 1인 기록 명령(`admin_request` 멱등, `h1:` 요청 지문). 사업자 잠금 → 계약·가맹점 이력(잠금 뒤) → Domain 제안 판정 → 제안 행 INSERT. **사업자·하위 행·version·변경 이력·Outbox 불변**(m-5).
 *   성공 reference = 제안 ID(`bcp_…`) — 응답 모양은 web이 제안 조회로 채운다(재생도 같은 reference).
 * - [applyWithEvidence]: 근거 경로(`executeApproved`, 키 `mop1.<approvalId>`). 근거 1~3 → 사업자 잠금 → lockedNow(근거 4) → 제안 읽기 → 이력(잠금 뒤) → Domain 실행 판정
 *   → diff 저장(version +1)·이력 `BusinessProfileUpdated`(approvalId·proposalId·요청자·승인자·대조 확인)·Outbox·근거 EXECUTED가 같은 TX. 성공 reference = businessId.
 */
interface BusinessCriticalChangeUseCase {
    fun propose(command: ProposeBusinessCriticalChangeCommand): AdminCommandResult

    fun applyWithEvidence(command: ApprovedBusinessCriticalChangeCommand): AdminCommandResult
}

/** 제안 명령(수정 본문 + version + 사유 + 근거 참조 — §5.1). [request]의 actor = 요청자(OPERATOR, 정규형 `admin:adm_<ULID>` — M-6 대조 대상). 번호 필드 없음(P-6). */
data class ProposeBusinessCriticalChangeCommand(
    val request: AdminRequest,
    val businessId: BusinessId,
    val version: Long,
    val reason: BusinessChangeReason?,
    val evidenceRef: EvidenceRef,
    val businessType: BusinessType,
    val displayName: DisplayName,
    val profile: BusinessProfile,
) {
    init {
        require(version >= 0) { "version must not be negative" }
    }
}

/**
 * 근거 경로 실행 명령(§3.2 실행 본문 — 근거 공통 필드 + {businessId, baseVersion, proposalId, contentDigest}). [request]의 actor = 승인자(C-17).
 * `mop1`·요청 지문의 명령 필드 순서 = [commandFields].
 */
data class ApprovedBusinessCriticalChangeCommand(
    override val request: AdminRequest,
    override val evidence: ApprovalEvidence,
    override val window: ApprovalWindow,
    val businessId: BusinessId,
    val baseVersion: Long,
    val proposalId: BusinessChangeProposalId,
    val contentDigest: BusinessChangeDigest,
) : ApprovedCommand {
    init {
        require(request.actor == evidence.approvedBy) { "the executing actor is the approver (C-17)" }
        require(baseVersion >= 0) { "base version must not be negative" }
    }

    override val operation: AdminOperation get() = AdminOperation.APPLY_BUSINESS_CRITICAL_CHANGE

    override val commandFields: List<String> get() = listOf(businessId.value, baseVersion.toString(), proposalId.value, contentDigest.value)
}

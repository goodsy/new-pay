package com.paycore.master.merchant.domain

/**
 * 승인된 사업자 중요정보 변경 실행 명령의 업무 값(BIZ-CRIT-APPROVAL-001 §3.2 실행 본문 — 근거 공통 필드 + {businessId, baseVersion, proposalId, contentDigest}).
 * ADMIN 승인함이 pinned 값으로 고정해 `mop1` 지문으로 보낸 값이다. 근거 판정 1~4(요청자 ≠ 승인자·실행 창·1회 사용·만료)는 실행기가 [ApprovalEvidence]로 먼저 한다.
 * toString에는 approvalId·proposalId만 넣는다.
 */
class ApprovedBusinessChange(
    val businessId: BusinessId,
    val baseVersion: Long,
    val proposalId: BusinessChangeProposalId,
    val contentDigest: BusinessChangeDigest,
    val evidence: ApprovalEvidence,
) {
    init {
        require(baseVersion >= 0) { "base version must not be negative" }
    }

    override fun toString(): String = "ApprovedBusinessChange(approvalId=${evidence.approvalId}, proposalId=${proposalId.value})"
}

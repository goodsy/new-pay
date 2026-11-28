package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.BusinessApprovalState
import com.paycore.master.merchant.application.provided.BusinessChangeEvidenceView
import com.paycore.master.merchant.application.provided.BusinessChangeProposalOriginal
import com.paycore.master.merchant.application.provided.BusinessChangeProposalView
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessId

/**
 * 사업자 중요정보 변경 조회 Port(BIZ-CRIT-APPROVAL-001 §3.1 — CQRS jOOQ Reader, 구현 `JooqBusinessChangeProposalReader`). **표시 전용 — 명령 서비스의 판정에 쓰지 않는다**(ArchUnit).
 * 모두 사업자 범위(다른 사업자의 제안·이력은 null).
 */
interface BusinessChangeProposalReader {
    fun approvalState(businessId: BusinessId): BusinessApprovalState?

    fun proposal(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalView?

    fun original(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalOriginal?

    fun evidence(businessId: BusinessId, historyId: Long): BusinessChangeEvidenceView?
}

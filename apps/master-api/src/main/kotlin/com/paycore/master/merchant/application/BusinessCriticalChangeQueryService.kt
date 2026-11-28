package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.BusinessApprovalState
import com.paycore.master.merchant.application.provided.BusinessChangeEvidenceView
import com.paycore.master.merchant.application.provided.BusinessChangeProposalOriginal
import com.paycore.master.merchant.application.provided.BusinessChangeProposalView
import com.paycore.master.merchant.application.provided.BusinessCriticalChangeQuery
import com.paycore.master.merchant.application.required.BusinessChangeProposalReader
import com.paycore.master.merchant.domain.BusinessChangeProposalId
import com.paycore.master.merchant.domain.BusinessId
import org.springframework.stereotype.Service

/** 사업자 중요정보 변경 조회 Use Case(BIZ-CRIT-APPROVAL-001 §5.1 — jOOQ Reader 위임, 읽기 전용 TX는 Reader가 연다). 명령 서비스는 이 서비스·Reader를 쓰지 않는다(ArchUnit). */
@Service
class BusinessCriticalChangeQueryService(private val reader: BusinessChangeProposalReader) : BusinessCriticalChangeQuery {
    override fun approvalState(businessId: BusinessId): BusinessApprovalState? = reader.approvalState(businessId)

    override fun proposal(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalView? = reader.proposal(businessId, proposalId)

    override fun original(businessId: BusinessId, proposalId: BusinessChangeProposalId): BusinessChangeProposalOriginal? = reader.original(businessId, proposalId)

    override fun evidence(businessId: BusinessId, historyId: Long): BusinessChangeEvidenceView? = reader.evidence(businessId, historyId)
}

package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.approval.application.required.MerchantPendingApprovalReader
import com.paycore.admin.merchantops.application.provided.MerchantPendingApprovals
import com.paycore.admin.merchantops.application.provided.PendingMerchantApproval
import org.springframework.stereotype.Service

/**
 * [MerchantPendingApprovals](merchantops 공개 SPI — MERCHANT-OPS-002 C-24): 가맹점 상세의 "승인 대기 중" 표시. 조회자 권한은 승인 목록과 같다(계정 현재 행 ACTIVE + 조회 4역할 —
 * [ApprovalQueryService]와 같은 규칙), 아니면 빈 목록. 의존 방향 approval → merchantops(provided)만(순환 없음 — merchantops는 이 구현을 모른다).
 */
@Service
class MerchantPendingApprovalQueryService(private val sessions: AdminSessionQuery, private val reader: MerchantPendingApprovalReader) : MerchantPendingApprovals {

    override fun pendingFor(viewerActorId: String, merchantId: String): List<PendingMerchantApproval> {
        val mayView = sessions.current(viewerActorId)?.let { it.isActive && it.roles.any { r -> r in VIEW_ROLES } } == true
        if (!mayView || !MERCHANT_ID.matches(merchantId)) return emptyList()
        return reader.unresolvedFor(merchantId).map { PendingMerchantApproval(it.approvalId, it.kind, it.status, it.revisionNo) }
    }

    private companion object {
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
        val MERCHANT_ID = Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}

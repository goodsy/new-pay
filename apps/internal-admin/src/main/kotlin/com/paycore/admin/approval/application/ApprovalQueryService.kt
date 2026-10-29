package com.paycore.admin.approval.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.ApprovalView
import com.paycore.admin.approval.application.provided.OwnerDetailView
import com.paycore.admin.approval.application.required.ApprovalListFilter
import com.paycore.admin.approval.application.required.ApprovalRequestListReader
import com.paycore.admin.approval.application.required.ApprovalRow
import com.paycore.admin.approval.domain.ApprovalExecutionModel
import com.paycore.admin.approval.domain.ApprovalKind
import org.springframework.stereotype.Service

/**
 * [ApprovalQuery] — 조회자 권한은 계정 현재 행(ACTIVE + VIEWER·OPERATOR·APPROVER·SECURITY_ADMIN 중 하나 — §4.5.1 "마스킹된 조회"), 조회는 jOOQ Reader(TX 없음).
 * 등록 코드는 조회 모델에 열 자체가 없다(§4.7.7 — `ResetCodeDisclosureSpec`).
 */
@Service
class ApprovalQueryService(private val sessions: AdminSessionQuery, private val reader: ApprovalRequestListReader) : ApprovalQuery {

    override fun list(viewerActorId: String, pendingOnly: Boolean): List<ApprovalView>? {
        if (!mayView(viewerActorId)) return null
        return reader.list(ApprovalListFilter(pendingOnly, if (pendingOnly) null else viewerActorId, LIMIT)).map(::view)
    }

    override fun detail(viewerActorId: String, approvalId: String): ApprovalView? =
        if (mayView(viewerActorId)) reader.detail(approvalId)?.let(::view) else null

    override fun isOwnerService(approvalId: String): Boolean? =
        reader.detail(approvalId)?.let { row -> ApprovalKind.entries.firstOrNull { it.name == row.kind }?.executionModel != ApprovalExecutionModel.ADMIN_OWNED }

    private fun mayView(actorId: String): Boolean = sessions.current(actorId)?.let { it.isActive && it.roles.any { r -> r in VIEW_ROLES } } == true

    private fun view(r: ApprovalRow) = ApprovalView(
        r.approvalId, r.kind, r.targetKind, r.targetActorId, r.targetRef, r.targetSnapshot, r.grantedRole, r.causeApprovalId, r.requestedBy, r.requestedByDisplayName, r.requestedAt,
        r.expiresAt, r.status, r.decidedBy, r.decidedByDisplayName, r.decidedAt, r.resultCode,
        r.owner?.let { o ->
            OwnerDetailView(
                o.amountMinor, o.currency, o.purposeKind, o.pinnedTarget, o.approvedAt, o.executeBefore, o.attemptCount, o.nextAttemptAt, o.lastResponseAt,
                o.lastFailure, o.integrityFlag, o.resendHaltedReason, o.ownerResultCode, o.ownerReference,
            )
        },
    )

    private companion object {
        const val LIMIT = 200
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
    }
}

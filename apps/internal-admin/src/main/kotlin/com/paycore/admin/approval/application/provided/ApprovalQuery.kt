package com.paycore.admin.approval.application.provided

import java.time.Instant

/**
 * 승인 조회(jOOQ Reader — CQRS). 조회자는 계정 현재 행으로 판정(ACTIVE + 마스킹된 조회 권한이 있는 역할 — §4.5.1). 등록 코드 원문은 어떤 조회에도 없다(§4.7.7).
 */
interface ApprovalQuery {
    /** [pendingOnly]면 승인함(PENDING 전체), 아니면 조회자 본인의 요청. 권한 없으면 null. */
    fun list(viewerActorId: String, pendingOnly: Boolean): List<ApprovalView>?

    /** 권한 없거나 없으면 null. */
    fun detail(viewerActorId: String, approvalId: String): ApprovalView?
}

data class ApprovalView(
    val approvalId: String,
    val kind: String,
    val targetActorId: String,
    val targetSnapshot: String,
    val grantedRole: String?,
    val causeApprovalId: String?,
    val requestedBy: String,
    val requestedByDisplayName: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val status: String,
    val decidedBy: String?,
    val decidedByDisplayName: String?,
    val decidedAt: Instant?,
    val resultCode: String?,
)

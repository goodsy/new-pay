package com.paycore.admin.approval.application.required

import java.time.Instant

/**
 * 승인 조회(jOOQ — CQRS, §14 Reader). 쓰기 없음, **조회 서비스만 쓴다**(ArchUnit — 명령 판정은 [ApprovalRequestRepository]의 잠근 행으로). 등록 코드는 어디에도 없다
 * (코드는 승인 응답 1회 — §4.7.7).
 */
interface ApprovalRequestListReader {
    fun list(filter: ApprovalListFilter): List<ApprovalRow>

    fun detail(approvalId: String): ApprovalRow?
}

/** [pendingOnly]: 승인함(PENDING), [requestedBy]: 내 요청. */
data class ApprovalListFilter(val pendingOnly: Boolean, val requestedBy: String?, val limit: Int)

data class ApprovalRow(
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

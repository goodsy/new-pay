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
    /** 대상 종류(ADMIN_ACCOUNT·PAYMENT — V5). 계정이면 [targetActorId], Payment면 [targetRef](다른 쪽 null). */
    val targetKind: String,
    val targetActorId: String?,
    val targetRef: String?,
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
    /** 소유 서비스 승인의 명령·실행 행(상세에만 — LEFT JOIN, ADMIN-PAYMENT-OPS-001 §5 jOOQ). 사유·근거 문구는 조회 모델에 넣지 않는다(I-98). */
    val owner: OwnerDetailRow? = null,
    /** 재매입 승인의 명령·실행 행(상세에만 — ACQ-ADMIN-001 B1-A). 사유 문구·근거는 넣지 않는다(I-98과 같은 규칙). */
    val acquiring: AcquiringDetailRow? = null,
    /** 가맹점 승인의 명령·실행 행(상세에만 — MERCHANT-OPS-002 B2-2). 근거 참조는 넣지 않는다(D-MOPS-008 ① — APPROVER·요청자만 별도 조회). */
    val merchant: MerchantOpsDetailRow? = null,
)

/** 가맹점 승인 명령 행(종류·가맹점·고정 대상 JSON) + 실행 행(승인 뒤에만). [ownerReference] = `ctr_…#n`, [ownerResultCode] = MASTER 거절 code. */
data class MerchantOpsDetailRow(
    val commandKind: String,
    val merchantId: String,
    val pinnedTarget: String,
    val approvedAt: Instant?,
    val executeBefore: Instant?,
    val attemptCount: Int?,
    val nextAttemptAt: Instant?,
    val lastResponseAt: Instant?,
    val lastFailure: String?,
    val integrityFlag: String?,
    val resendHaltedReason: String?,
    val ownerResultCode: String?,
    val ownerReference: String?,
)

/** 재매입 명령 행(원 파일·사유 코드·고정 대상 JSON) + 실행 행(승인 뒤에만). [ownerReference] = 새 파일 ID, [ownerResultCode] = NOT_REQUIRED 또는 거절 code. */
data class AcquiringDetailRow(
    val originalFileId: String,
    val reasonCode: String,
    val pinnedTarget: String,
    val approvedAt: Instant?,
    val executeBefore: Instant?,
    val attemptCount: Int?,
    val nextAttemptAt: Instant?,
    val lastResponseAt: Instant?,
    val lastFailure: String?,
    val integrityFlag: String?,
    val resendHaltedReason: String?,
    val ownerResultCode: String?,
    val ownerReference: String?,
)

/** 명령 행(요청 고정 값) + 실행 행(승인 뒤에만 — 없으면 실행 열 null). */
data class OwnerDetailRow(
    val amountMinor: Long,
    val currency: String,
    val purposeKind: String,
    /** 요청 시점 고정 대상 JSON(권위 `pinned_target` — 화면의 "고정 값 vs 현재 값" 비교). */
    val pinnedTarget: String,
    val approvedAt: Instant?,
    val executeBefore: Instant?,
    val attemptCount: Int?,
    val nextAttemptAt: Instant?,
    val lastResponseAt: Instant?,
    val lastFailure: String?,
    val integrityFlag: String?,
    val resendHaltedReason: String?,
    val ownerResultCode: String?,
    val ownerReference: String?,
)

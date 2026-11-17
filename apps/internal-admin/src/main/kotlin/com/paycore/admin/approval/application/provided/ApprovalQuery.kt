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

    /**
     * 승인 명령의 0단계 경로 선택(비잠금·비권위 — §4.10 M-6): 소유 서비스 종류면 true, ADMIN 소유면 false, 없으면 null. 권위 판정은 각 서비스의 잠근 행이 다시 한다
     * (기존 단일 TX 경로는 잠근 행이 소유 서비스면 fail-closed).
     */
    fun isOwnerService(approvalId: String): Boolean?
}

data class ApprovalView(
    val approvalId: String,
    val kind: String,
    /** 대상 종류(ADMIN_ACCOUNT·PAYMENT). 계정이면 [targetActorId], Payment면 [targetRef](다른 쪽 null — ADMIN-PAYMENT-OPS-001). */
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
    /** 소유 서비스 승인 상세(명령·실행 — S8 화면용, 사유·근거 문구 없음 — I-98). 목록·ADMIN 소유 종류는 null. */
    val owner: OwnerDetailView? = null,
    /** 재매입 승인 상세(ACQ-ADMIN-001 B1-A §5.2 — 원 파일·사유 코드·고정 레코드·실행 결과, 사유 문구·근거 없음). 목록·다른 종류는 null. */
    val acquiring: AcquiringDetailView? = null,
    /** 가맹점 승인 상세(MERCHANT-OPS-002 B2-2 — 고정 대상(등록자 포함)·실행 결과, 근거 참조 없음). 목록·다른 종류는 null. */
    val merchant: MerchantOpsDetailView? = null,
    /** MERCHANT-CODE-001 MC-3a(§5.6.4): 가맹점 대상의 내부 키(mer_ — 기존 행·새 행 모두), 그 밖 대상은 null. 화면은 라벨 대응에만 쓰고 그리지 않는다(MC-3c). */
    val targetMerchantId: String? = null,
)

data class MerchantOpsDetailView(
    val commandKind: String,
    val merchantId: String,
    /** 요청 시점 고정 대상 JSON(권위 `pinned_target` — 가맹점·계약·Revision·Scope·기간·registeredBy). */
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

data class AcquiringDetailView(
    val originalFileId: String,
    val reasonCode: String,
    /** 요청 시점 고정 레코드 JSON(권위 `pinned_target` — 화면의 "고정 값 vs 현재 값"). */
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

data class OwnerDetailView(
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

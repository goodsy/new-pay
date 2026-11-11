package com.paycore.admin.approval.domain

/** 가맹점 2인 승인의 요청 시점 고정 대상(MERCHANT-OPS-002 §9.5 pinned — 계약 승인 [PinnedContractApprovalTarget], 첫 MID 발급 [PinnedFirstMidTarget], B3-1 개통 승인 [PinnedActivationApprovalTarget]·Site 심사 승인 [PinnedSiteReviewApprovalTarget]). */
sealed interface PinnedMerchantTarget {
    val merchantId: String

    /** 결정적 JSON(필드 순서 고정) — 승인 행 `target_snapshot`과 명령 행 `pinned_target`에 같은 값. */
    fun toSnapshotJson(): String
}

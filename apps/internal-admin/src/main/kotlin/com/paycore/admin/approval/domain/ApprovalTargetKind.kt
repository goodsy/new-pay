package com.paycore.admin.approval.domain

/** 승인 대상 종류(§4.9 — DB `admin_approval_request.target_kind`). [ADMIN_ACCOUNT]는 `target_actor_id`, [PAYMENT]는 `target_ref`에 둔다(V5 `ck_…_target`). */
enum class ApprovalTargetKind {
    ADMIN_ACCOUNT,
    PAYMENT,

    /** FINANCIAL 매입 파일(ACQ-ADMIN-001 B1-A — 재매입 원 파일 ID, `target_ref` — V7 `ck_…_target`). */
    ACQUISITION_FILE,

    /**
     * MASTER 가맹점(MERCHANT-OPS-002 B2-2 — 대상 버전 0 — C-16, V9 `ck_…_target`). 표기 `target_ref` = 요청 시점 가맹점 코드(MERCHANT-CODE-001 MC-3a — 그 전 행은 mer_, V15 CHECK),
     * 내부 키 `target_merchant_id` = merchantId(mer_ — 미해결 키·MASTER 호출 기준).
     */
    MERCHANT,
}

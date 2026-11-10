package com.paycore.admin.approval.domain

/** 승인 대상 종류(§4.9 — DB `admin_approval_request.target_kind`). [ADMIN_ACCOUNT]는 `target_actor_id`, [PAYMENT]는 `target_ref`에 둔다(V5 `ck_…_target`). */
enum class ApprovalTargetKind {
    ADMIN_ACCOUNT,
    PAYMENT,

    /** FINANCIAL 매입 파일(ACQ-ADMIN-001 B1-A — 재매입 원 파일 ID, `target_ref` — V7 `ck_…_target`). */
    ACQUISITION_FILE,

    /** MASTER 가맹점(MERCHANT-OPS-002 B2-2 — `target_ref` = merchantId, 대상 버전 0 — C-16, V9 `ck_…_target`). */
    MERCHANT,
}

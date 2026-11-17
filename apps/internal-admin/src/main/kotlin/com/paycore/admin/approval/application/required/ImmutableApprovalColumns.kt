package com.paycore.admin.approval.application.required

/**
 * 승인 행의 생성 불변 열(승인 TX 0단계 비잠금 읽기 — 1단계 잠금 뒤 같은지 단언, rev 0.5 n-1). [targetRef]는 대상 식별자(계정이면 `target_actor_id`,
 * Payment면 `target_ref` — ADMIN-PAYMENT-OPS-001 §4.11). [targetActorId]는 기존 단일 TX 경로용 계정 접근자로, Payment 대상이면 [IllegalStateException](M-6 fail-closed).
 */
data class ImmutableApprovalColumns(
    val kind: String,
    val targetKind: String,
    val targetRef: String,
    val grantedRole: String?,
    /** MERCHANT-CODE-001 MC-3a(§5.6.4): 가맹점 대상의 내부 키(`target_merchant_id` — 코드 표기 행의 0단계 감사 대상 복원용, 다른 대상은 null). */
    val targetMerchantId: String? = null,
) {
    val targetActorId: String
        get() {
            check(targetKind == ACCOUNT) { "not an account target — the ADMIN-owned single-TX path never handles owner-service approvals (M-6)" }
            return targetRef
        }

    private companion object {
        const val ACCOUNT = "ADMIN_ACCOUNT"
    }
}

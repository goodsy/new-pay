package com.paycore.admin.approval.application.required

/**
 * 가맹점별 미해결 승인 조회(jOOQ — CQRS, §14 Reader, MERCHANT-OPS-002 C-24). 쓰기 없음, **조회 서비스만 쓴다**(ArchUnit). 대상 `MERCHANT`, 상태 PENDING·EXECUTING·EXECUTION_UNKNOWN,
 * 최근 요청 순. 근거 참조·고정 대상 값은 없다.
 */
interface MerchantPendingApprovalReader {
    fun unresolvedFor(merchantId: String): List<MerchantPendingApprovalRow>
}

/** [revisionNo] = 계약 승인의 대상 Revision(고정 대상 JSON에서 — 첫 MID 발급은 null). */
data class MerchantPendingApprovalRow(val approvalId: String, val kind: String, val status: String, val revisionNo: Int? = null)

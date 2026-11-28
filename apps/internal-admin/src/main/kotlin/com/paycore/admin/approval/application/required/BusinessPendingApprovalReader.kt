package com.paycore.admin.approval.application.required

import java.time.Instant

/**
 * 사업자별 미해결 중요정보 변경 승인 조회(BIZ-CRIT-APPROVAL-001 §3.4·§5.2 — jOOQ CQRS Reader, 쓰기 없음, **조회 서비스만 쓴다**). 대상 `BUSINESS`, 상태 PENDING·EXECUTING·EXECUTION_UNKNOWN.
 * 미해결은 사업자당 하나(UNIQUE `BUSINESS:<biz_>`)라 결과는 0..1행. 내용·요청자는 읽지 않는다.
 */
interface BusinessPendingApprovalReader {
    fun unresolvedFor(businessId: String): BusinessPendingApprovalRow?
}

data class BusinessPendingApprovalRow(val approvalId: String, val status: String, val requestedAt: Instant)

package com.paycore.admin.approval.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 사업자 중요정보 변경 승인의 변경 후 원문 조회(BIZ-CRIT-APPROVAL-001 §5.2 `GET /api/approvals/{apr}/proposal-original` — M-1·R2-m4). APPROVER와 그 요청의 요청자만, 미해결 상태에서만,
 * 열람(거부 시도 포함)마다 감사, 사업자 조회 창 공유 속도 제한.
 */
interface BusinessChangeOriginalQuery {
    fun original(actorId: String, approvalId: String, context: AdminRequestContext): BusinessChangeOriginalResult
}

/** 화면 매핑: Found 200, Forbidden 403, NotFound 404, NotOpen 409 `APPROVAL_NOT_OPEN`, RateLimited 429, Unavailable 503. */
sealed interface BusinessChangeOriginalResult {
    data class Found(val approvalId: String, val content: Map<String, Any?>) : BusinessChangeOriginalResult {
        override fun toString(): String = "Found(approvalId=$approvalId, ***)"
    }

    data object Forbidden : BusinessChangeOriginalResult

    data object NotFound : BusinessChangeOriginalResult

    data object NotOpen : BusinessChangeOriginalResult

    data object RateLimited : BusinessChangeOriginalResult

    data class Unavailable(val code: String) : BusinessChangeOriginalResult
}

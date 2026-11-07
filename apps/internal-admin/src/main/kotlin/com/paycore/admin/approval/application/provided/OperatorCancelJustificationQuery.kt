package com.paycore.admin.approval.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 운영자 보정 취소의 근거 참조·사유 문구 별도 권한 조회(ADMIN-PAYMENT-OPS-001 S8, D-OPS-010): 조회자 계정 현재 행이 ACTIVE이고 APPROVER 역할이거나 그 승인의 요청자 본인일 때만
 * [JustificationResult.Found](재매입 승인은 [JustificationResult.EvidenceFound] — ACQ-ADMIN-001 D-ACQA-005). 시도마다(허용·거부) 감사 1행 — detail·사유 열에 문구를 넣지 않는다. 감사를 남기지 못하면 문구를 돌려주지 않는다.
 */
interface OperatorCancelJustificationQuery {
    fun view(viewerActorId: String, approvalId: String, context: AdminRequestContext): JustificationResult
}

sealed interface JustificationResult {
    class Found(val approvalId: String, val purposeReference: String, val reason: String) : JustificationResult {
        override fun toString(): String = "Found(approvalId=$approvalId, ***)"
    }

    /** 재매입 승인의 근거 참조(ACQ-ADMIN-001 D-ACQA-005 — 같은 판정·감사, 사유 문구는 돌려주지 않는다). */
    class EvidenceFound(val approvalId: String, val evidenceRef: String) : JustificationResult {
        override fun toString(): String = "EvidenceFound(approvalId=$approvalId, ***)"
    }

    data object Forbidden : JustificationResult

    data object NotFound : JustificationResult

    data object AuditUnavailable : JustificationResult

    /** 조회자별 분당 상한 초과(I-131 — 데이터를 읽지 않고 감사도 남기지 않는다, 지표만). */
    data object RateLimited : JustificationResult
}

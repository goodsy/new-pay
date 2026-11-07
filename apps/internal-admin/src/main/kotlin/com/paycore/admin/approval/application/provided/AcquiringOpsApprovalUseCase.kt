package com.paycore.admin.approval.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 재매입 2인 승인 요청(ACQ-ADMIN-001 B1-A — D-ACQA-002 요청 OPERATOR, D-ACQA-003 원 파일 하나의 레코드 1..50건). 입력 검사(TX 없음) → FINANCIAL 미리 보기(조회 키, TX 밖 — 묶음
 * 전부 eligible이어야, advisory) → TX(승인 행 INSERT — 같은 원 파일 미해결이면 `DUPLICATE_UNRESOLVED` → 요청자 잠금·재검사 → 명령 행 INSERT → 감사). 거부 감사는 별도 TX.
 * 승인은 [OwnerServiceApprovalUseCase.approve](종류 분기), 거절·철회·만료는 [ApprovalUseCase](같은 판정표, 감사 target_ref = 파일 ID).
 */
interface AcquiringOpsApprovalUseCase {
    fun requestReacquisition(command: RequestReacquisition): ApprovalOutcome
}

/** 재매입 요청 입력(서버는 자르거나 정규화하지 않는다). toString에 근거·사유가 없다. */
data class RequestReacquisition(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val fileId: String,
    val seqs: List<Int>,
    val reasonCode: String,
    val evidenceRef: String,
    val reason: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "RequestReacquisition(requester=$requesterActorId, fileId=$fileId, seqs=${seqs.size})"
}

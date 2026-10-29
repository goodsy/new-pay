package com.paycore.admin.approval.application.provided

import com.paycore.admin.account.application.provided.AdminRequestContext

/**
 * 소유 서비스 승인(ADMIN-PAYMENT-OPS-001 §4.12·§4.13 — `OPERATOR_CORRECTION_CANCEL`). 기존 단일 TX 경로([ApprovalUseCase.approve])와 달리 2-TX다:
 * - [requestOperatorCancel](OPERATOR): 입력 검사(TX 없음) → PAYMENT 대상 조회(TX 밖) → advisory → TX(승인 행 INSERT — 같은 Payment 미해결이면 `DUPLICATE_UNRESOLVED`,
 *   1213이면 `RETRY_LATER` → 요청자 잠금·재검사 → 명령 행 INSERT → 감사). 거부 감사는 별도 TX.
 * - [approve](APPROVER + 직전 TOTP): A0 비잠금 읽기 → A 대상 재조회(TX 밖, 진행 중 취소면 `TARGET_BUSY` — TOTP 미소비) → TX1(잠금·판정·TOTP 소비·요청자 재검사·고정 값 비교·
 *   oct1·본문 저장·`EXECUTING`) → C 서명 POST(TX 밖) → TX2(결과 반영 — §4.15). 거절·철회·만료는 [ApprovalUseCase](같은 판정표, 감사 target_ref).
 */
interface OwnerServiceApprovalUseCase {
    fun requestOperatorCancel(command: RequestOperatorCancel): ApprovalOutcome

    fun approve(command: DecideApproval): ApprovalOutcome
}

/** 운영자 보정 취소 요청 입력(§4.12 — 서버는 앞뒤 공백을 자르지 않고 거부, 정규화 없음 — §4.18). toString에 사유·근거가 없다. */
data class RequestOperatorCancel(
    val requesterActorId: String,
    val sessionGeneration: Long,
    val paymentId: String,
    val amountMinor: Long,
    val purposeKind: String,
    val purposeReference: String,
    val reason: String,
    val context: AdminRequestContext,
) {
    override fun toString(): String = "RequestOperatorCancel(requester=$requesterActorId, paymentId=$paymentId, amountMinor=$amountMinor)"
}

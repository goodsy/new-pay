package com.paycore.master.merchant.application.provided

/**
 * 2인 승인 명령의 결과 불명 회복용 MASTER 호출(MERCHANT-OPS-002 §9.4 — C-18 개정, PAYMENT·FINANCIAL 결정 조회 선례). 둘 다 **업무 본문(잠금·Domain·쓰기)을 부르지 않는다**.
 *
 * - [decision]: 근거 행(`admin_approval_evidence`) 한 행 읽기(읽기 전용 TX — 쓰기 0). [ApprovalDecision.None]은 "아직 커밋된 결정 없음"일 뿐 **효과 없음의 근거가 아니다**
 *   (진행 중 TX가 곧 커밋할 수 있다). `APPROVAL_ID_ALREADY_USED` 거절은 근거 행이 없어 나오지 않는다(원래 approvalId의 결정이 나온다).
 * - [confirmDeadline]: 실행과 같은 operation·키·지문으로 멱등 예약 → 결과가 있으면 재생, 판정 1·2는 저장(근거 행 `DEADLINE_CONFIRM`), 판정 3은 저장(근거 행 없음),
 *   MASTER 시각 < 기한이면 **TX 전체 rollback**(예약도 남기지 않음) 뒤 [DeadlineConfirmationResult.NotYetDue], 기한 이상이면 `APPROVAL_EXPIRED` 저장 + 근거 행(`DEADLINE_CONFIRM`).
 *   기술 실패는 [AdminCommandFailure](같은 키 진행 중 = RequestInProgress).
 */
interface ApprovalDecisionUseCase {
    fun decision(approvalId: String): ApprovalDecision

    fun confirmDeadline(command: ApprovedCommand): DeadlineConfirmationResult
}

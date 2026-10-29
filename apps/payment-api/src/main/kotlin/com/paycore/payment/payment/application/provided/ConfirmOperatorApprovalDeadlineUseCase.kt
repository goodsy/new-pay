package com.paycore.payment.payment.application.provided

/**
 * 기한 확인 전용 호출(ADMIN-PAYMENT-OPS-001 §4.4.5, D-OPS-009 — 진입점은 ADMIN 채널 `OperatorDeadlineConfirmationController`만, ArchUnit). 본문은 그 승인의 저장된 실행 명령 그대로다.
 * ① 결정 있음 → 그 결정(confirmed=false) ② 옛 취소 행 → legacy STARTED ③ PAYMENT 시각 `now < executeBefore` → NONE(기록 없음) ④ 그 밖 → REJECTED(APPROVAL_EXECUTION_EXPIRED)
 * 결정 기록(origin DEADLINE_CONFIRMATION) + 거절 감사 1행(confirmed=true). **취소를 시작하지 않는다**(ArchUnit `deadlineConfirmationNeverStartsCancel`). 일시 실패는
 * [CancelPaymentFailure](503).
 */
interface ConfirmOperatorApprovalDeadlineUseCase {
    fun confirm(command: OperatorCorrectionCancelCommand): OperatorDeadlineConfirmation
}

/** 호출 뒤의 기록 결정과 이 호출이 EXPIRED를 새로 기록했는가([confirmed]). */
data class OperatorDeadlineConfirmation(val decision: OperatorDecisionView, val confirmed: Boolean)

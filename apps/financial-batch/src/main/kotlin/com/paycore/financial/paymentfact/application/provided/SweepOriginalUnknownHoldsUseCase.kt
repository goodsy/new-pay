package com.paycore.financial.paymentfact.application.provided

/**
 * D-FFR-003 자동 해제의 일괄 처리(SWEEP). Inbox 원승인 수신 TX가 평소 해제를 판정하므로, 이 처리는 **기능 도입 전에 이미 원승인이 도착해 있던** ORIGINAL_UNKNOWN 보류를
 * 같은 규칙으로 판정한다(Payment마다 FINANCIAL 로컬 TX 하나 — 결제별 직렬화 행 잠금 아래, 바깥 TX와 별도). UNIQUE(hold_id)라 재실행은 아무것도 더 쓰지 않는다.
 * 매입 Job의 첫 step이 부른다(ACQUIRING-001 §4.2).
 */
interface SweepOriginalUnknownHoldsUseCase {
    /** 최대 [maxPayments]개의 Payment를 처리한다. */
    fun sweep(maxPayments: Int): HoldReleaseSweepResult
}

/** [payments] = 처리한 Payment 수, [released]·[reviewRequired] = 기록한 판정 수. */
data class HoldReleaseSweepResult(val payments: Int, val released: Int, val reviewRequired: Int)

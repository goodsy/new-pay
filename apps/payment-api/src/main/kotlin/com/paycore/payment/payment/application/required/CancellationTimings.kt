package com.paycore.payment.payment.application.required

import java.time.Duration

/**
 * 취소 전송·회복 시간 설정(CANCEL-001 CN-29 — `paycore.cancellation.timing.*`, **Demo, 운영값 아님, 운영 기본값 없음**). 승인 설정(AuthorizationTimings)과 같은 의미·같은 판정 구조이며
 * 값은 따로 버전 관리한다(D-AUTH-009). [recoveryConfigVersion]은 회복 일정 행·이관 이벤트에 기록한다(Auditability).
 */
interface CancellationTimings {
    val dispatchLease: Duration
    val safetyMargin: Duration
    val preparedDeadline: Duration
    val gateRetryInterval: Duration
    val inquiryInterval: Duration
    val inquiryBudget: Int
    val maxAge: Duration
    val slowInquiryInterval: Duration
    val recoveryBatchSize: Int
    val recoveryConfigVersion: String
}

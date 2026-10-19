package com.paycore.payment.payment.application.required

import java.time.Duration

/**
 * 승인 전송·회복 시간 설정(AUTH-001 §4.4·§4.5·§6 — **Demo, 운영값 아님, 운영 기본값 없음**).
 * - [dispatchLease]: TXd 통과 뒤 전송자가 결과를 반영할 시간. 지나면 회복 Worker가 Attempt를 OUTCOME_UNKNOWN(DISPATCH_LEASE_EXPIRED)으로 표시한다.
 * - [safetyMargin]: 전송자는 `lease − margin` 뒤에는 connect하지 않는다(AU-07·AU-34).
 * - [preparedDeadline]: PREPARED Attempt가 전송되지 않은 채 이 시간이 지나면 회복이 fencing한다. 게이트의 일시 Unavailable은 이 시각까지 재시도(AU-33).
 * - 회복: 조회 간격·예산·최대 연령(넘으면 대사 이관 — 상태 불변, §6.2·D-AUTH-009), 이관 뒤 느린 조회 간격(AU-22).
 */
interface AuthorizationTimings {
    val dispatchLease: Duration
    val safetyMargin: Duration
    val preparedDeadline: Duration
    val gateRetryInterval: Duration
    val inquiryInterval: Duration
    val inquiryBudget: Int
    val maxAge: Duration
    val slowInquiryInterval: Duration
    val recoveryBatchSize: Int

    /** 회복 설정 버전(검토 #3 m-4 — 회복 일정 행·이관 이벤트에 기록, Auditability). */
    val recoveryConfigVersion: String
}

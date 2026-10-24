package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.WebhookRetrySchedule
import java.time.Duration

/**
 * delivery 시간 설정 Port(기본값 없는 설정 — D-WH-006 Demo 값, WH-17). [attemptBudget] = 한 시도 최대 시간(DNS 예산 + 전체 요청 기한 + 여유). [claimLease]는 그보다 길다(기동 검사).
 * 회차 안 전달은 차례로 보내므로 보내기 직전마다 `now + attemptBudget ≤ claimUntil`을 확인한다(검토 #2 H-1).
 */
interface MerchantWebhookDeliveryTimings {
    val schedule: WebhookRetrySchedule
    val claimLease: Duration
    val attemptBudget: Duration
}

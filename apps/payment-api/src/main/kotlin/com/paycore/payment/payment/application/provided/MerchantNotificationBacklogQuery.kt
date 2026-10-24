package com.paycore.payment.payment.application.provided

/**
 * fan-out 적체(WEBHOOK-001 §11·검토 #1 n-3): 비종결 실패(FANOUT_FAILED) 행 수·가장 오래된 실패 연령(초)·PENDING 행 수·가장 오래된 PENDING 연령(초). gauge 값이며 판정이 아니다.
 * `failedRows > 0`은 경보 대상이다(이상 조합·읽기 불가 payload — 같은 Payment의 뒤 통지를 막고 있다). 경보 임계값은 운영값(LIVE Gate).
 */
data class MerchantNotificationBacklog(
    val failedRows: Long,
    val oldestFailedAgeSeconds: Long,
    val pendingRows: Long,
    val oldestPendingAgeSeconds: Long,
    /** S5(§11): PENDING 전달 수와 가장 이른 다음 시도 예정 시각이 지난 시간(아직 오지 않았으면 0 — 전달 지연), 포기(GAVE_UP) 누적 수(> 0 증가 = 가맹점 도달 실패 경보 근거). */
    val deliveryPendingRows: Long,
    val oldestDeliveryDueAgeSeconds: Long,
    val gaveUpRows: Long,
)

interface MerchantNotificationBacklogQuery {
    fun backlog(): MerchantNotificationBacklog
}

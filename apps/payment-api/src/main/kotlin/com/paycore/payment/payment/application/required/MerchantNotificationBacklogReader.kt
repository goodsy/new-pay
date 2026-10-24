package com.paycore.payment.payment.application.required

import java.time.Instant

/**
 * fan-out·delivery 적체 원자료(비권위 — gauge 전용). [oldestFailedAt]은 미종결 실패 행 중 가장 이른 첫 실패 시각, [oldestPendingAt]은 PENDING 행 중 가장 이른 업무 발생 시각.
 * delivery(S5): [deliveryPendingRows] = PENDING 전달 수, [oldestDeliveryDueAt] = PENDING 전달 중 가장 이른 다음 시도 예정 시각, [gaveUpRows] = 포기한 전달 수(종결 — 누적).
 */
data class MerchantNotificationBacklogCounts(
    val failedRows: Long,
    val oldestFailedAt: Instant?,
    val pendingRows: Long,
    val oldestPendingAt: Instant?,
    val deliveryPendingRows: Long,
    val oldestDeliveryDueAt: Instant?,
    val gaveUpRows: Long,
)

/** fan-out·delivery 적체 jOOQ 조회 Port(WEBHOOK-001 검토 #1 n-3 — FANOUT_FAILED 수·최고 연령 경보 근거, PENDING 적체 = fan-out 지연, S5 — 전달 적체·포기 수). 판정에 쓰지 않는다. */
interface MerchantNotificationBacklogReader {
    fun counts(): MerchantNotificationBacklogCounts
}

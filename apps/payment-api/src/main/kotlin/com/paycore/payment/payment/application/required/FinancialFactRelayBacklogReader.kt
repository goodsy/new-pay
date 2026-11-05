package com.paycore.payment.payment.application.required

import java.time.Instant

/**
 * Relay 적체 원자료(비권위 — gauge 전용). [pendingRows] = PENDING 전달 행 수, [oldestPendingOccurredAt] = 그 Outbox 행 중 가장 이른 업무 발생 시각(전달 지연 = Outbox lag),
 * [retryingRows] = 한 번 이상 실패한 PENDING 행 수, [quarantinedRows] = 격리 행 수(종결 아님 — > 0이면 경보), [undiscoveredRows] = 아직 전달 행이 없는 FINANCIAL_FACT Outbox 행 수.
 */
data class FinancialFactRelayBacklogCounts(
    val pendingRows: Long,
    val oldestPendingOccurredAt: Instant?,
    val retryingRows: Long,
    val quarantinedRows: Long,
    val undiscoveredRows: Long,
)

/** Relay 적체 jOOQ 조회 Port(FIN-FACT-RELAY-001 §6 Observability). 판정에 쓰지 않는다. */
interface FinancialFactRelayBacklogReader {
    fun counts(): FinancialFactRelayBacklogCounts
}

package com.paycore.payment.payment.application.provided

/**
 * PAYMENT → FINANCIAL 결제 사실 Relay 적체(FIN-FACT-RELAY-001 §6 Observability — gauge 값이며 판정이 아니다). `quarantinedRows > 0`과 `oldestPendingAgeSeconds` 증가는 경보 대상이다
 * (FINANCIAL에 아직 반영되지 않은 확정 금융 사실). 경보 임계값은 운영값(LIVE Gate).
 */
data class FinancialFactRelayBacklog(
    val pendingRows: Long,
    val oldestPendingAgeSeconds: Long,
    val retryingRows: Long,
    val quarantinedRows: Long,
    val undiscoveredRows: Long,
)

interface FinancialFactRelayBacklogQuery {
    fun backlog(): FinancialFactRelayBacklog
}

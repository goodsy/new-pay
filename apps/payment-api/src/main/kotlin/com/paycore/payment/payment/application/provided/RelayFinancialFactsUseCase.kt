package com.paycore.payment.payment.application.provided

import java.time.Duration

/**
 * PAYMENT → FINANCIAL 결제 사실 Relay 한 회차(FIN-FACT-RELAY-001 §4.4, D-ACQ-001). 스케줄 adapter와 시험이 부른다. 외부 호출은 DB TX 밖.
 * 전달 기술 설정(배치 크기·claim lease·backoff)은 업무 정책이 아니다(Demo 값 — 운영값 LIVE Gate).
 */
interface RelayFinancialFactsUseCase {
    fun relayOnce(command: RelayFinancialFactsCommand): FinancialFactRelayReport
}

data class RelayFinancialFactsCommand(
    val batchSize: Int,
    val claimLease: Duration,
    val backoffInitial: Duration,
    val backoffMax: Duration,
    val backoffJitterRatio: Double,
) {
    init {
        require(batchSize >= 1) { "batch size must be positive" }
        require(claimLease.isPositive) { "claim lease must be positive" }
        require(backoffInitial.isPositive && backoffMax >= backoffInitial) { "backoff must be positive and max >= initial" }
        require(backoffJitterRatio >= 0.0 && backoffJitterRatio < 1.0) { "backoff jitter ratio must be within [0, 1)" }
    }
}

/**
 * 회차 결과. [failed] = 행 처리 중 예상 밖 예외(그 행은 claim 만료 뒤 다시 집힌다). [deferred] = 남은 claim lease가 전송 예산 + 여유보다 짧아 보내지 않고 claim을 푼 행
 * (다음 회차·다른 인스턴스가 보낸다 — 검토 M-1).
 */
data class FinancialFactRelayReport(
    val discovered: Int,
    val claimed: Int,
    val delivered: Int,
    val retried: Int,
    val quarantined: Int,
    val claimLost: Int,
    val failed: Int,
    val deferred: Int = 0,
)

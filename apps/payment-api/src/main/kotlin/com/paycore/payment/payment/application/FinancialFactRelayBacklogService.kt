package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.FinancialFactRelayBacklog
import com.paycore.payment.payment.application.provided.FinancialFactRelayBacklogQuery
import com.paycore.payment.payment.application.required.FinancialFactRelayBacklogReader
import com.paycore.payment.payment.application.required.PaymentClock
import org.springframework.stereotype.Service
import java.time.Duration

/** Relay 적체 관찰: jOOQ 수와 서버 시각 기준 최고 연령 — 판정이 아니라 gauge 값이다(fan-out 적체 서비스와 같은 방식). */
@Service
class FinancialFactRelayBacklogService(private val clock: PaymentClock, private val reader: FinancialFactRelayBacklogReader) : FinancialFactRelayBacklogQuery {
    override fun backlog(): FinancialFactRelayBacklog {
        val c = reader.counts()
        val age = c.oldestPendingOccurredAt?.let { Duration.between(it, clock.now()).seconds.coerceAtLeast(0) } ?: 0
        return FinancialFactRelayBacklog(c.pendingRows, age, c.retryingRows, c.quarantinedRows, c.undiscoveredRows)
    }
}

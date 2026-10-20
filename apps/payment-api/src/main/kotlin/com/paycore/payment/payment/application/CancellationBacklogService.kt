package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.CancellationBacklog
import com.paycore.payment.payment.application.provided.CancellationBacklogQuery
import com.paycore.payment.payment.application.required.CancellationViewReader
import com.paycore.payment.payment.application.required.PaymentClock
import org.springframework.stereotype.Service
import java.time.Duration

/** 취소 적체 관찰(CANCEL-001 §10 Observability): jOOQ 수와 지금 시각(서버 clock) 기준 최고 연령 — 판정이 아니라 gauge 값이다. */
@Service
class CancellationBacklogService(private val clock: PaymentClock, private val reader: CancellationViewReader) : CancellationBacklogQuery {
    override fun backlog(): CancellationBacklog {
        val c = reader.backlog()
        val age = c.oldestUnknownPreparedAt?.let { Duration.between(it, clock.now()).seconds.coerceAtLeast(0) } ?: 0
        return CancellationBacklog(c.unknownCancellations, age, c.escalatedOpenSchedules)
    }
}

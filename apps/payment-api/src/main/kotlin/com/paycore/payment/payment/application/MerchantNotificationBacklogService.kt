package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.MerchantNotificationBacklog
import com.paycore.payment.payment.application.provided.MerchantNotificationBacklogQuery
import com.paycore.payment.payment.application.required.MerchantNotificationBacklogReader
import com.paycore.payment.payment.application.required.PaymentClock
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/** fan-out 적체 관찰(WEBHOOK-001 n-3): jOOQ 수와 서버 시각 기준 최고 연령 — 판정이 아니라 gauge 값이다(취소 적체 서비스와 같은 방식). */
@Service
class MerchantNotificationBacklogService(private val clock: PaymentClock, private val reader: MerchantNotificationBacklogReader) : MerchantNotificationBacklogQuery {
    override fun backlog(): MerchantNotificationBacklog {
        val c = reader.counts()
        val now = clock.now()
        return MerchantNotificationBacklog(
            c.failedRows, age(c.oldestFailedAt, now), c.pendingRows, age(c.oldestPendingAt, now), c.deliveryPendingRows, age(c.oldestDeliveryDueAt, now), c.gaveUpRows,
        )
    }

    private fun age(since: Instant?, now: Instant): Long = since?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0
}

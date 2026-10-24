package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.MERCHANT_NOTIFICATION_FAILURE
import com.paycore.payment.jooq.generated.Tables.MERCHANT_WEBHOOK_DELIVERY
import com.paycore.payment.jooq.generated.Tables.OUTBOX_EVENT
import com.paycore.payment.payment.application.required.MerchantNotificationBacklogCounts
import com.paycore.payment.payment.application.required.MerchantNotificationBacklogReader
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * fan-out·delivery 적체 수(CQRS 조회 측, jOOQ — WEBHOOK-001 n-3·S5). delivery는 `ix_merchant_webhook_delivery_due(status, next_attempt_at, delivery_id)`의 status 접두로 센다. 쓰기 없음, 읽기 전용 TX 하나, 비권위. 시각은 [UtcWallClock] 문자열(관례 §9.1). 인덱스 `ix_outbox_event_merchant_notification`로 미종결 행만 본다.
 */
@Component
class JooqMerchantNotificationBacklogReader(private val dsl: DSLContext) : MerchantNotificationBacklogReader {

    @Transactional(readOnly = true)
    override fun counts(): MerchantNotificationBacklogCounts {
        val o = OUTBOX_EVENT
        val f = MERCHANT_NOTIFICATION_FAILURE
        val oldestFailed = UtcWallClock.iso(DSL.min(f.FIRST_FAILED_AT))
        val failed = dsl.select(DSL.count(), oldestFailed).from(o).join(f).on(f.OUTBOX_ID.eq(o.ID))
            .where(o.MERCHANT_NOTIFICATION_STATUS.eq("FANOUT_FAILED")).fetchOne()
        val oldestPending = UtcWallClock.iso(DSL.min(o.OCCURRED_AT))
        val pending = dsl.select(DSL.count(), oldestPending).from(o).where(o.MERCHANT_NOTIFICATION_STATUS.eq("PENDING")).fetchOne()
        val d = MERCHANT_WEBHOOK_DELIVERY
        val oldestDue = UtcWallClock.iso(DSL.min(d.NEXT_ATTEMPT_AT))
        val due = dsl.select(DSL.count(), oldestDue).from(d).where(d.STATUS.eq("PENDING")).fetchOne()
        val gaveUp = dsl.selectCount().from(d).where(d.STATUS.eq("GAVE_UP")).fetchOne(0, Int::class.java) ?: 0
        return MerchantNotificationBacklogCounts(
            failedRows = (failed?.get(0, Int::class.java) ?: 0).toLong(),
            oldestFailedAt = failed?.get(oldestFailed)?.let(UtcWallClock::parse),
            pendingRows = (pending?.get(0, Int::class.java) ?: 0).toLong(),
            oldestPendingAt = pending?.get(oldestPending)?.let(UtcWallClock::parse),
            deliveryPendingRows = (due?.get(0, Int::class.java) ?: 0).toLong(),
            oldestDeliveryDueAt = due?.get(oldestDue)?.let(UtcWallClock::parse),
            gaveUpRows = gaveUp.toLong(),
        )
    }
}

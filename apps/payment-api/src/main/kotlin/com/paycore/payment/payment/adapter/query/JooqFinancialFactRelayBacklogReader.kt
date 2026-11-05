package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.FINANCIAL_FACT_RELAY
import com.paycore.payment.jooq.generated.Tables.OUTBOX_EVENT
import com.paycore.payment.payment.application.required.FinancialFactRelayBacklogCounts
import com.paycore.payment.payment.application.required.FinancialFactRelayBacklogReader
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Relay 적체 수(CQRS 조회 측, jOOQ — FIN-FACT-RELAY-001 §6). 쓰기 없음, 읽기 전용 TX 하나, 비권위(gauge 전용). 시각은 [UtcWallClock] 문자열(관례 §9.1).
 * PENDING·격리는 `ix_financial_fact_relay_claim(status, outbox_id)` 접두로 세고, 미발견 수는 `ix_outbox_event_financial_pending`(미전달 행만) + PK anti-join(D-FFR-001).
 */
@Component
class JooqFinancialFactRelayBacklogReader(private val dsl: DSLContext) : FinancialFactRelayBacklogReader {

    @Transactional(readOnly = true)
    override fun counts(): FinancialFactRelayBacklogCounts {
        val r = FINANCIAL_FACT_RELAY
        val o = OUTBOX_EVENT
        val oldest = UtcWallClock.iso(DSL.min(o.OCCURRED_AT))
        val pending = dsl.select(DSL.count(), oldest).from(r).join(o).on(o.ID.eq(r.OUTBOX_ID)).where(r.STATUS.eq("PENDING")).fetchOne()
        val retrying = dsl.selectCount().from(r).where(r.STATUS.eq("PENDING").and(r.ATTEMPTS.gt(0))).fetchOne(0, Int::class.java) ?: 0
        val quarantined = dsl.selectCount().from(r).where(r.STATUS.eq("QUARANTINED")).fetchOne(0, Int::class.java) ?: 0
        val undiscovered = dsl.selectCount().from(o)
            .where(o.EVENT_CATEGORY.eq("FINANCIAL_FACT").and(o.PUBLISH_STATUS.eq("PENDING")).and(DSL.notExists(DSL.selectOne().from(r).where(r.OUTBOX_ID.eq(o.ID)))))
            .fetchOne(0, Int::class.java) ?: 0
        return FinancialFactRelayBacklogCounts(
            pendingRows = (pending?.get(0, Int::class.java) ?: 0).toLong(),
            oldestPendingOccurredAt = pending?.get(oldest)?.let(UtcWallClock::parse),
            retryingRows = retrying.toLong(),
            quarantinedRows = quarantined.toLong(),
            undiscoveredRows = undiscovered.toLong(),
        )
    }
}

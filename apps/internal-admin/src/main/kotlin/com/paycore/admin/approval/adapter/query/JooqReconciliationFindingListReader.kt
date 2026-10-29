package com.paycore.admin.approval.adapter.query

import com.paycore.admin.approval.application.required.ReconciliationCheckpointRow
import com.paycore.admin.approval.application.required.ReconciliationFindingListReader
import com.paycore.admin.approval.application.required.ReconciliationFindingRow
import com.paycore.admin.jooq.generated.Tables.ADMIN_OPS_RECONCILIATION_CHECKPOINT
import com.paycore.admin.jooq.generated.Tables.ADMIN_OPS_RECONCILIATION_FINDING
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant

/** [ReconciliationFindingListReader] jOOQ 구현(읽기 전용 — `finding_id` 내림차순, 시각은 UTC 문자열로 읽어 JVM 시간대와 무관하게). */
@Component
class JooqReconciliationFindingListReader(private val dsl: DSLContext) : ReconciliationFindingListReader {
    private val f = ADMIN_OPS_RECONCILIATION_FINDING
    private val c = ADMIN_OPS_RECONCILIATION_CHECKPOINT

    override fun list(limit: Int): List<ReconciliationFindingRow> =
        dsl.select(f.FINDING_ID, f.KIND, f.APPROVAL_ID, f.PAYMENT_REF, f.DETAIL_CODE, time(f.FOUND_AT, FOUND_AT))
            .from(f).orderBy(f.FINDING_ID.desc()).limit(limit)
            .fetch { r ->
                ReconciliationFindingRow(
                    r.get(f.FINDING_ID)!!, r.get(f.KIND)!!, r.get(f.APPROVAL_ID)!!, r.get(f.PAYMENT_REF), r.get(f.DETAIL_CODE), instant(r, FOUND_AT)!!,
                )
            }

    override fun openCounts(): Map<String, Long> =
        dsl.select(f.KIND, DSL.count()).from(f).groupBy(f.KIND).fetch().associate { it.value1()!! to it.value2()!!.toLong() }

    override fun checkpoint(): ReconciliationCheckpointRow? =
        dsl.select(time(c.LAST_RUN_AT, LAST_RUN_AT), time(c.LAST_FULL_SCAN_AT, LAST_FULL_SCAN_AT)).from(c).limit(1)
            .fetchOne { r -> ReconciliationCheckpointRow(instant(r, LAST_RUN_AT), instant(r, LAST_FULL_SCAN_AT)) }

    private fun instant(r: Record, alias: String): Instant? = r.get(alias, String::class.java)?.let(Instant::parse)

    private fun time(field: org.jooq.Field<*>, alias: String) = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field).`as`(alias)

    private companion object {
        const val FOUND_AT = "found_at_text"
        const val LAST_RUN_AT = "last_run_at_text"
        const val LAST_FULL_SCAN_AT = "last_full_scan_at_text"
    }
}

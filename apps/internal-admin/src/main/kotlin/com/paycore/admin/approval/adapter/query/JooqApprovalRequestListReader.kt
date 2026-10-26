package com.paycore.admin.approval.adapter.query

import com.paycore.admin.approval.application.required.ApprovalListFilter
import com.paycore.admin.approval.application.required.ApprovalRequestListReader
import com.paycore.admin.approval.application.required.ApprovalRow
import com.paycore.admin.jooq.generated.Tables.ADMIN_APPROVAL_REQUEST
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * [ApprovalRequestListReader] jOOQ 구현(CQRS 조회 — 쓰기 없음, 조회 서비스 전용). 시각은 관례 §9.1대로 `DATE_FORMAT` 문자열로 읽는다.
 */
@Component
class JooqApprovalRequestListReader(private val dsl: DSLContext) : ApprovalRequestListReader {
    private val t = ADMIN_APPROVAL_REQUEST

    override fun list(filter: ApprovalListFilter): List<ApprovalRow> {
        val conditions = mutableListOf<Condition>()
        if (filter.pendingOnly) conditions += t.STATUS.eq("PENDING")
        filter.requestedBy?.let { conditions += t.REQUESTED_BY.eq(it) }
        return select().where(conditions).orderBy(t.REQUESTED_AT.desc(), t.APPROVAL_ID.desc()).limit(filter.limit).fetch(::row)
    }

    override fun detail(approvalId: String): ApprovalRow? = select().where(t.APPROVAL_ID.eq(approvalId)).fetchOne(::row)

    private fun select() = dsl.select(
        t.APPROVAL_ID, t.KIND, t.TARGET_ACTOR_ID, DSL.field("CAST({0} AS CHAR)", String::class.java, t.TARGET_SNAPSHOT).`as`(SNAPSHOT), t.GRANTED_ROLE,
        t.CAUSE_APPROVAL_ID, t.REQUESTED_BY, t.REQUESTED_BY_DISPLAY_NAME, time(t.REQUESTED_AT, REQUESTED_AT), time(t.EXPIRES_AT, EXPIRES_AT), t.STATUS,
        t.DECIDED_BY, t.DECIDED_BY_DISPLAY_NAME, time(t.DECIDED_AT, DECIDED_AT), t.RESULT_CODE,
    ).from(t)

    private fun time(field: org.jooq.Field<*>, alias: String) = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field).`as`(alias)

    private fun row(r: Record) = ApprovalRow(
        approvalId = r.get(t.APPROVAL_ID)!!, kind = r.get(t.KIND)!!, targetActorId = r.get(t.TARGET_ACTOR_ID)!!, targetSnapshot = r.get(SNAPSHOT, String::class.java)!!,
        grantedRole = r.get(t.GRANTED_ROLE), causeApprovalId = r.get(t.CAUSE_APPROVAL_ID), requestedBy = r.get(t.REQUESTED_BY)!!,
        requestedByDisplayName = r.get(t.REQUESTED_BY_DISPLAY_NAME)!!, requestedAt = Instant.parse(r.get(REQUESTED_AT, String::class.java)),
        expiresAt = Instant.parse(r.get(EXPIRES_AT, String::class.java)), status = r.get(t.STATUS)!!, decidedBy = r.get(t.DECIDED_BY),
        decidedByDisplayName = r.get(t.DECIDED_BY_DISPLAY_NAME), decidedAt = r.get(DECIDED_AT, String::class.java)?.let(Instant::parse),
        resultCode = r.get(t.RESULT_CODE),
    )

    private companion object {
        const val SNAPSHOT = "target_snapshot_text"
        const val REQUESTED_AT = "requested_at_text"
        const val EXPIRES_AT = "expires_at_text"
        const val DECIDED_AT = "decided_at_text"
    }
}

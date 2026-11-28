package com.paycore.admin.approval.adapter.query

import com.paycore.admin.approval.application.required.BusinessPendingApprovalReader
import com.paycore.admin.approval.application.required.BusinessPendingApprovalRow
import com.paycore.admin.jooq.generated.Tables.ADMIN_APPROVAL_REQUEST
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/** [BusinessPendingApprovalReader] jOOQ 구현(BIZ-CRIT-APPROVAL-001 — ADMIN DB 안 조회, 기존 색인 `ix_admin_approval_request_target_ref (target_kind, target_ref)`). */
@Component
class JooqBusinessPendingApprovalReader(private val dsl: DSLContext) : BusinessPendingApprovalReader {
    private val t = ADMIN_APPROVAL_REQUEST

    override fun unresolvedFor(businessId: String): BusinessPendingApprovalRow? =
        dsl.select(t.APPROVAL_ID, t.STATUS, t.REQUESTED_AT).from(t)
            .where(t.TARGET_KIND.eq("BUSINESS").and(t.TARGET_REF.eq(businessId)).and(t.STATUS.`in`("PENDING", "EXECUTING", "EXECUTION_UNKNOWN")))
            .orderBy(t.REQUESTED_AT.desc(), t.APPROVAL_ID.desc())
            .limit(1)
            .fetchOne { BusinessPendingApprovalRow(it.value1()!!, it.value2()!!, it.value3()!!.toInstant(ZoneOffset.UTC)) }
}

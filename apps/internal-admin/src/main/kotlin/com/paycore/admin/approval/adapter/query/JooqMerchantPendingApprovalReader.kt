package com.paycore.admin.approval.adapter.query

import com.paycore.admin.approval.application.required.MerchantPendingApprovalReader
import com.paycore.admin.approval.application.required.MerchantPendingApprovalRow
import com.paycore.admin.jooq.generated.Tables.ADMIN_APPROVAL_REQUEST
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component

/**
 * [MerchantPendingApprovalReader] jOOQ 구현(MERCHANT-OPS-002 C-24 — ADMIN DB 안 조회, MASTER DB와 JOIN 없음). 기존 `ix_admin_approval_request_target_ref (target_kind, target_ref)`를 쓴다.
 * 미해결은 가맹점당 하나(UNIQUE — C-20)라 결과는 보통 0..1행이지만, 표시용으로 상한 10을 둔다.
 */
@Component
class JooqMerchantPendingApprovalReader(private val dsl: DSLContext) : MerchantPendingApprovalReader {
    private val t = ADMIN_APPROVAL_REQUEST

    override fun unresolvedFor(merchantId: String): List<MerchantPendingApprovalRow> =
        dsl.select(
            t.APPROVAL_ID, t.KIND, t.STATUS,
            DSL.field("CAST(JSON_EXTRACT({0}, '$.revisionNo') AS SIGNED)", Int::class.javaObjectType, t.TARGET_SNAPSHOT),
        ).from(t)
            .where(t.TARGET_KIND.eq("MERCHANT").and(t.TARGET_REF.eq(merchantId)).and(t.STATUS.`in`("PENDING", "EXECUTING", "EXECUTION_UNKNOWN")))
            .orderBy(t.REQUESTED_AT.desc(), t.APPROVAL_ID.desc())
            .limit(LIMIT)
            .fetch { MerchantPendingApprovalRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4().takeIf { _ -> it.value2() == "MERCHANT_CONTRACT_APPROVAL" }) }

    private companion object {
        const val LIMIT = 10
    }
}

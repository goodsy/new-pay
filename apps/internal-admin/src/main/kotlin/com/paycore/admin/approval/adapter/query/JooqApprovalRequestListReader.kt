package com.paycore.admin.approval.adapter.query

import com.paycore.admin.approval.application.required.AcquiringDetailRow
import com.paycore.admin.approval.application.required.ApprovalListFilter
import com.paycore.admin.approval.application.required.ApprovalRequestListReader
import com.paycore.admin.approval.application.required.ApprovalRow
import com.paycore.admin.approval.application.required.OwnerDetailRow
import com.paycore.admin.approval.application.required.MerchantOpsDetailRow
import com.paycore.admin.jooq.generated.Tables.ADMIN_ACQUIRING_OPS_COMMAND
import com.paycore.admin.jooq.generated.Tables.ADMIN_MERCHANT_OPS_COMMAND
import com.paycore.admin.jooq.generated.Tables.ADMIN_OPERATOR_CANCEL_COMMAND
import com.paycore.admin.jooq.generated.Tables.ADMIN_OWNER_EXECUTION
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

    /** 상세: 소유 서비스 승인이면 명령·실행 행을 LEFT JOIN(같은 비권위 조회 — 명령 판정에 쓰지 않는다). 운영자 보정 취소는 사유·근거 열을 고르지 않는다(I-98·D-OPS-010), 재매입은 근거 참조만(리뷰 m-2). */
    override fun detail(approvalId: String): ApprovalRow? {
        val base = select().where(t.APPROVAL_ID.eq(approvalId)).fetchOne(::row) ?: return null
        val c = ADMIN_OPERATOR_CANCEL_COMMAND
        val e = ADMIN_OWNER_EXECUTION
        val owner = dsl.select(
            c.AMOUNT_MINOR, c.CURRENCY, c.PURPOSE_KIND, DSL.field("CAST({0} AS CHAR)", String::class.java, c.PINNED_TARGET).`as`(PINNED),
            time(e.APPROVED_AT, APPROVED_AT), time(e.EXECUTE_BEFORE, EXECUTE_BEFORE), e.ATTEMPT_COUNT, time(e.NEXT_ATTEMPT_AT, NEXT_ATTEMPT_AT),
            time(e.LAST_RESPONSE_AT, LAST_RESPONSE_AT), e.LAST_FAILURE, e.INTEGRITY_FLAG, e.RESEND_HALTED_REASON, e.OWNER_RESULT_CODE, e.OWNER_REFERENCE,
        ).from(c).leftJoin(e).on(e.APPROVAL_ID.eq(c.APPROVAL_ID)).where(c.APPROVAL_ID.eq(approvalId)).fetchOne { r ->
            OwnerDetailRow(
                amountMinor = r.get(c.AMOUNT_MINOR)!!, currency = r.get(c.CURRENCY)!!, purposeKind = r.get(c.PURPOSE_KIND)!!, pinnedTarget = r.get(PINNED, String::class.java)!!,
                approvedAt = instant(r, APPROVED_AT), executeBefore = instant(r, EXECUTE_BEFORE), attemptCount = r.get(e.ATTEMPT_COUNT),
                nextAttemptAt = instant(r, NEXT_ATTEMPT_AT), lastResponseAt = instant(r, LAST_RESPONSE_AT), lastFailure = r.get(e.LAST_FAILURE),
                integrityFlag = r.get(e.INTEGRITY_FLAG), resendHaltedReason = r.get(e.RESEND_HALTED_REASON), ownerResultCode = r.get(e.OWNER_RESULT_CODE),
                ownerReference = r.get(e.OWNER_REFERENCE),
            )
        }
        if (owner != null) return base.copy(owner = owner)
        // ACQ-ADMIN-001 B1-A: 재매입 명령·실행 행. 근거 참조·사유 문구 열은 고르지 않는다(rev 0.6 D-ACQA-005 — 근거 참조는 APPROVER·요청자 본인만 별도 조회, 사유 문구 I-4).
        val a = ADMIN_ACQUIRING_OPS_COMMAND
        val acquiring = dsl.select(
            a.ORIGINAL_FILE_ID, a.REASON_CODE, DSL.field("CAST({0} AS CHAR)", String::class.java, a.PINNED_TARGET).`as`(PINNED),
            time(e.APPROVED_AT, APPROVED_AT), time(e.EXECUTE_BEFORE, EXECUTE_BEFORE), e.ATTEMPT_COUNT, time(e.NEXT_ATTEMPT_AT, NEXT_ATTEMPT_AT),
            time(e.LAST_RESPONSE_AT, LAST_RESPONSE_AT), e.LAST_FAILURE, e.INTEGRITY_FLAG, e.RESEND_HALTED_REASON, e.OWNER_RESULT_CODE, e.OWNER_REFERENCE,
        ).from(a).leftJoin(e).on(e.APPROVAL_ID.eq(a.APPROVAL_ID)).where(a.APPROVAL_ID.eq(approvalId)).fetchOne { r ->
            AcquiringDetailRow(
                originalFileId = r.get(a.ORIGINAL_FILE_ID)!!, reasonCode = r.get(a.REASON_CODE)!!, pinnedTarget = r.get(PINNED, String::class.java)!!,
                approvedAt = instant(r, APPROVED_AT), executeBefore = instant(r, EXECUTE_BEFORE), attemptCount = r.get(e.ATTEMPT_COUNT),
                nextAttemptAt = instant(r, NEXT_ATTEMPT_AT), lastResponseAt = instant(r, LAST_RESPONSE_AT), lastFailure = r.get(e.LAST_FAILURE),
                integrityFlag = r.get(e.INTEGRITY_FLAG), resendHaltedReason = r.get(e.RESEND_HALTED_REASON), ownerResultCode = r.get(e.OWNER_RESULT_CODE),
                ownerReference = r.get(e.OWNER_REFERENCE),
            )
        }
        if (acquiring != null) return base.copy(acquiring = acquiring)
        // MERCHANT-OPS-002 B2-2: 가맹점 승인 명령·실행 행. 근거 참조 열은 고르지 않는다(D-MOPS-008 ① — APPROVER·요청자만 별도 조회).
        val m = ADMIN_MERCHANT_OPS_COMMAND
        val merchant = dsl.select(
            m.COMMAND_KIND, m.MERCHANT_ID, DSL.field("CAST({0} AS CHAR)", String::class.java, m.PINNED_TARGET).`as`(PINNED),
            time(e.APPROVED_AT, APPROVED_AT), time(e.EXECUTE_BEFORE, EXECUTE_BEFORE), e.ATTEMPT_COUNT, time(e.NEXT_ATTEMPT_AT, NEXT_ATTEMPT_AT),
            time(e.LAST_RESPONSE_AT, LAST_RESPONSE_AT), e.LAST_FAILURE, e.INTEGRITY_FLAG, e.RESEND_HALTED_REASON, e.OWNER_RESULT_CODE, e.OWNER_REFERENCE,
        ).from(m).leftJoin(e).on(e.APPROVAL_ID.eq(m.APPROVAL_ID)).where(m.APPROVAL_ID.eq(approvalId)).fetchOne { r ->
            MerchantOpsDetailRow(
                commandKind = r.get(m.COMMAND_KIND)!!, merchantId = r.get(m.MERCHANT_ID)!!, pinnedTarget = r.get(PINNED, String::class.java)!!,
                approvedAt = instant(r, APPROVED_AT), executeBefore = instant(r, EXECUTE_BEFORE), attemptCount = r.get(e.ATTEMPT_COUNT),
                nextAttemptAt = instant(r, NEXT_ATTEMPT_AT), lastResponseAt = instant(r, LAST_RESPONSE_AT), lastFailure = r.get(e.LAST_FAILURE),
                integrityFlag = r.get(e.INTEGRITY_FLAG), resendHaltedReason = r.get(e.RESEND_HALTED_REASON), ownerResultCode = r.get(e.OWNER_RESULT_CODE),
                ownerReference = r.get(e.OWNER_REFERENCE),
            )
        }
        return if (merchant == null) base else base.copy(merchant = merchant)
    }

    private fun instant(r: Record, alias: String): Instant? = r.get(alias, String::class.java)?.let(Instant::parse)

    private fun select() = dsl.select(
        t.APPROVAL_ID, t.KIND, t.TARGET_KIND, t.TARGET_ACTOR_ID, t.TARGET_REF, DSL.field("CAST({0} AS CHAR)", String::class.java, t.TARGET_SNAPSHOT).`as`(SNAPSHOT), t.GRANTED_ROLE,
        t.CAUSE_APPROVAL_ID, t.REQUESTED_BY, t.REQUESTED_BY_DISPLAY_NAME, time(t.REQUESTED_AT, REQUESTED_AT), time(t.EXPIRES_AT, EXPIRES_AT), t.STATUS,
        t.DECIDED_BY, t.DECIDED_BY_DISPLAY_NAME, time(t.DECIDED_AT, DECIDED_AT), t.RESULT_CODE,
        // MERCHANT-CODE-001 MC-3a(§5.6.4): 가맹점 대상의 내부 키(기존 행은 target_ref = mer_, 새 행은 target_merchant_id) — 그 밖 대상은 NULL.
        DSL.`when`(t.TARGET_KIND.eq(MERCHANT), DSL.coalesce(t.TARGET_MERCHANT_ID, t.TARGET_REF)).`as`(TARGET_MERCHANT),
    ).from(t)

    private fun time(field: org.jooq.Field<*>, alias: String) = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field).`as`(alias)

    private fun row(r: Record) = ApprovalRow(
        approvalId = r.get(t.APPROVAL_ID)!!, kind = r.get(t.KIND)!!, targetKind = r.get(t.TARGET_KIND)!!,
        // 계정 대상만 target_actor_id, Payment 대상은 target_ref(V5 ck_…_target) — 한쪽은 NULL이다(S4 리뷰 4: non-null 단언이 목록 전체를 500으로 만들던 결함).
        targetActorId = r.get(t.TARGET_ACTOR_ID), targetRef = r.get(t.TARGET_REF), targetSnapshot = r.get(SNAPSHOT, String::class.java)!!,
        grantedRole = r.get(t.GRANTED_ROLE), causeApprovalId = r.get(t.CAUSE_APPROVAL_ID), requestedBy = r.get(t.REQUESTED_BY)!!,
        requestedByDisplayName = r.get(t.REQUESTED_BY_DISPLAY_NAME)!!, requestedAt = Instant.parse(r.get(REQUESTED_AT, String::class.java)),
        expiresAt = Instant.parse(r.get(EXPIRES_AT, String::class.java)), status = r.get(t.STATUS)!!, decidedBy = r.get(t.DECIDED_BY),
        decidedByDisplayName = r.get(t.DECIDED_BY_DISPLAY_NAME), decidedAt = r.get(DECIDED_AT, String::class.java)?.let(Instant::parse),
        resultCode = r.get(t.RESULT_CODE), targetMerchantId = r.get(TARGET_MERCHANT, String::class.java),
    )

    private companion object {
        const val SNAPSHOT = "target_snapshot_text"
        const val TARGET_MERCHANT = "target_merchant_key"
        const val MERCHANT = "MERCHANT"
        const val REQUESTED_AT = "requested_at_text"
        const val EXPIRES_AT = "expires_at_text"
        const val DECIDED_AT = "decided_at_text"
        const val PINNED = "pinned_target_text"
        const val APPROVED_AT = "approved_at_text"
        const val EXECUTE_BEFORE = "execute_before_text"
        const val NEXT_ATTEMPT_AT = "next_attempt_at_text"
        const val LAST_RESPONSE_AT = "last_response_at_text"
    }
}

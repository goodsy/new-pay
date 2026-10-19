package com.paycore.payment.payment.adapter.query

import com.paycore.payment.jooq.generated.Tables.AUTHORIZATION_ATTEMPT
import com.paycore.payment.jooq.generated.Tables.AUTHORIZATION_RECOVERY_SCHEDULE
import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD
import com.paycore.payment.jooq.generated.Tables.INCIDENT_HOLD_RELEASE
import com.paycore.payment.payment.application.required.AuthorizationBacklogCounts
import com.paycore.payment.payment.application.required.AuthorizationBacklogReader
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 승인 적체 수(CQRS 조회 측, jOOQ — AUTH-001 §9 Observability gauge). 쓰기 없음, 읽기 전용 TX 하나, 비권위. 시각은 [UtcWallClock]으로 읽는다(관례 §9.1).
 * UNKNOWN 수·최고 연령은 인덱스 `ix_authorization_attempt_state(state, prepared_at, …)`, 이관 수는 `ix_authorization_recovery_schedule_due(closed_at, …)`를 쓴다.
 * 사건 보류 "활성" = 해제 기록(`incident_hold_release`, hold당 최대 1)이 없는 hold.
 */
@Component
class JooqAuthorizationBacklogReader(private val dsl: DSLContext) : AuthorizationBacklogReader {

    @Transactional(readOnly = true)
    override fun counts(): AuthorizationBacklogCounts {
        val t = AUTHORIZATION_ATTEMPT
        val oldest = UtcWallClock.iso(DSL.min(t.PREPARED_AT))
        val unknown = dsl.select(DSL.count(), oldest).from(t).where(t.STATE.eq("OUTCOME_UNKNOWN")).fetchOne()!!
        val s = AUTHORIZATION_RECOVERY_SCHEDULE
        val escalated = dsl.fetchCount(s, s.CLOSED_AT.isNull.and(s.ESCALATED_AT.isNotNull))
        val h = INCIDENT_HOLD
        val r = INCIDENT_HOLD_RELEASE
        val holds = dsl.fetchCount(h, DSL.notExists(DSL.selectOne().from(r).where(r.INCIDENT_HOLD_ID.eq(h.ID))))
        return AuthorizationBacklogCounts(
            unknownAttempts = unknown.value1().toLong(),
            oldestUnknownPreparedAt = UtcWallClock.parse(unknown.value2()),
            escalatedOpenSchedules = escalated.toLong(),
            activeIncidentHolds = holds.toLong(),
        )
    }
}

package com.paycore.admin.audit.adapter.query

import com.paycore.admin.audit.application.provided.AuditEntry
import com.paycore.admin.audit.application.provided.AuditFilter
import com.paycore.admin.audit.application.required.AdminAuditReader
import com.paycore.admin.audit.domain.AuditScope
import com.paycore.admin.jooq.generated.Tables.ADMIN_AUDIT_EVENT
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [AdminAuditReader] jOOQ 구현(CQRS 조회 — 쓰기 없음). [AuditScope.Self]는 SQL `actor_id = ?`로 강제하고 필터의 actorId는 쓰지 않는다(m-11).
 * 최신순(event_id 내림차순) keyset 페이지. 시각은 관례 §9.1대로 `DATE_FORMAT` 문자열로 읽고(UTC 벽시계 그대로) 비교 값은 UTC 리터럴 `CAST(… AS DATETIME(3))`로 묶는다.
 */
@Component
class JooqAdminAuditReader(private val dsl: DSLContext) : AdminAuditReader {

    override fun search(scope: AuditScope, filter: AuditFilter): List<AuditEntry> {
        val t = ADMIN_AUDIT_EVENT
        val conditions = mutableListOf<Condition>()
        when (scope) {
            is AuditScope.Self -> conditions += t.ACTOR_ID.eq(scope.actorId)
            AuditScope.All -> filter.actorId?.let { conditions += t.ACTOR_ID.eq(it) }
        }
        filter.targetActorId?.let { conditions += t.TARGET_ACTOR_ID.eq(it) }
        filter.action?.let { conditions += t.ACTION.eq(it) }
        filter.from?.let { conditions += DSL.condition("{0} >= CAST({1} AS DATETIME(3))", t.OCCURRED_AT, DSL.value(literal(it))) }
        filter.to?.let { conditions += DSL.condition("{0} < CAST({1} AS DATETIME(3))", t.OCCURRED_AT, DSL.value(literal(it))) }
        filter.beforeEventId?.let { conditions += t.EVENT_ID.lt(it) }
        val occurredAt = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, t.OCCURRED_AT)
        return dsl.select(
            t.EVENT_ID, occurredAt, t.ACTOR_KIND, t.ACTOR_ID, t.ACTOR_DISPLAY_NAME, t.EXECUTOR_ACTOR_ID, t.ACTION, t.TARGET_ACTOR_ID, t.RESULT_CODE,
            t.APPROVAL_ID, t.CORRELATION_ID, t.DETAIL, t.REASON,
        ).from(t).where(conditions).orderBy(t.EVENT_ID.desc()).limit(filter.limit).fetch { r ->
            AuditEntry(
                eventId = r.get(t.EVENT_ID)!!,
                occurredAt = Instant.parse(r.get(occurredAt)),
                actorKind = r.get(t.ACTOR_KIND)!!,
                actorId = r.get(t.ACTOR_ID),
                actorDisplayName = r.get(t.ACTOR_DISPLAY_NAME),
                executorActorId = r.get(t.EXECUTOR_ACTOR_ID),
                action = r.get(t.ACTION)!!,
                targetActorId = r.get(t.TARGET_ACTOR_ID),
                resultCode = r.get(t.RESULT_CODE)!!,
                approvalId = r.get(t.APPROVAL_ID),
                correlationId = r.get(t.CORRELATION_ID)!!,
                detail = r.get(t.DETAIL),
                reason = r.get(t.REASON),
            )
        }
    }

    private fun literal(at: Instant): String = LITERAL.format(at)

    private companion object {
        val LITERAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
    }
}

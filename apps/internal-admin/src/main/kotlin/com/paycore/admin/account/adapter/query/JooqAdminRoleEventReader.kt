package com.paycore.admin.account.adapter.query

import com.paycore.admin.account.application.required.AdminRoleEventReader
import com.paycore.admin.account.application.required.RoleChangeRow
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.RoleEventType
import com.paycore.admin.jooq.generated.Tables.ADMIN_ROLE_EVENT
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * [AdminRoleEventReader](jOOQ — adapter.query). 역할·종류·원인 열만 SELECT(행위자 정보는 원인 후보에 필요 없다). 인덱스 `ix_admin_role_event_target`(대상, event_id).
 */
@Component
class JooqAdminRoleEventReader(private val dsl: DSLContext) : AdminRoleEventReader {
    private val t = ADMIN_ROLE_EVENT

    override fun changes(target: AdminActorId): List<RoleChangeRow> =
        dsl.select(t.ROLE, t.EVENT_TYPE, t.CAUSE_REF).from(t).where(t.TARGET_ACTOR_ID.eq(target.value)).orderBy(t.EVENT_ID)
            .fetch { r -> RoleChangeRow(AdminRole.valueOf(r.get(t.ROLE)!!), RoleEventType.valueOf(r.get(t.EVENT_TYPE)!!), r.get(t.CAUSE_REF)) }
}

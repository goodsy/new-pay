package com.paycore.admin.account.adapter.query

import com.paycore.admin.account.application.required.AdminSessionReader
import com.paycore.admin.account.application.required.SessionAccountRow
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.jooq.generated.Tables.ADMIN_ACCOUNT
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/** [AdminSessionReader] jOOQ 구현 — PK 단건 비잠금 읽기(요청마다). 역할은 MySQL SET 쉼표 문자열. */
@Component
class JooqAdminSessionReader(private val dsl: DSLContext) : AdminSessionReader {
    override fun find(actorId: AdminActorId): SessionAccountRow? {
        val t = ADMIN_ACCOUNT
        return dsl.select(t.ACTOR_ID, t.DISPLAY_NAME, t.STATUS, t.SESSION_GENERATION, t.SECURITY_VERSION, t.ACTIVE_ROLES)
            .from(t).where(t.ACTOR_ID.eq(actorId.value)).fetchOne { r ->
                SessionAccountRow(
                    actorId = AdminActorId(r.get(t.ACTOR_ID)!!),
                    displayName = r.get(t.DISPLAY_NAME)!!,
                    status = AccountStatus.valueOf(r.get(t.STATUS)!!),
                    sessionGeneration = r.get(t.SESSION_GENERATION)!!,
                    securityVersion = r.get(t.SECURITY_VERSION)!!,
                    activeRoles = r.get(t.ACTIVE_ROLES)!!.split(',').filter { it.isNotEmpty() }.map(AdminRole::valueOf).toSet(),
                )
            }
    }
}

package com.paycore.admin.account.adapter.query

import com.paycore.admin.account.application.required.AdminAccountListReader
import com.paycore.admin.account.application.required.AdminAccountRow
import com.paycore.admin.jooq.generated.Tables.ADMIN_ACCOUNT
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/** [AdminAccountListReader] jOOQ 구현(CQRS 조회 — 쓰기 없음). 비밀값 열(비밀번호 해시·TOTP 암호문)은 SELECT하지 않는다. */
@Component
class JooqAdminAccountListReader(private val dsl: DSLContext) : AdminAccountListReader {
    private val t = ADMIN_ACCOUNT

    override fun list(limit: Int, includeLoginName: Boolean): List<AdminAccountRow> =
        dsl.select(listOfNotNull(t.ACTOR_ID, t.LOGIN_NAME.takeIf { includeLoginName }, t.DISPLAY_NAME, t.STATUS, t.ACTIVE_ROLES, t.SUSPENDED_ROLES, t.PENDING_PURPOSE))
            .from(t).orderBy(t.DISPLAY_NAME, t.ACTOR_ID).limit(limit)
            .fetch { r ->
                AdminAccountRow(
                    r.get(t.ACTOR_ID)!!, if (includeLoginName) r.get(t.LOGIN_NAME) else null, r.get(t.DISPLAY_NAME)!!, r.get(t.STATUS)!!,
                    roles(r.get(t.ACTIVE_ROLES)), roles(r.get(t.SUSPENDED_ROLES)), r.get(t.PENDING_PURPOSE),
                )
            }

    private fun roles(value: Any?): List<String> = value?.toString()?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
}

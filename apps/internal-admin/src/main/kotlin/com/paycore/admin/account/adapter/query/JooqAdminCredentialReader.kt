package com.paycore.admin.account.adapter.query

import com.paycore.admin.account.application.required.AdminCredentialReader
import com.paycore.admin.account.application.required.StoredCredential
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.PasswordHash
import com.paycore.admin.jooq.generated.Tables.ADMIN_ACCOUNT
import org.jooq.DSLContext
import org.springframework.stereotype.Component

/**
 * [AdminCredentialReader] jOOQ 구현 — 비잠금 단건 조회(호출자 TX 없음: 자동 커밋 읽기). login_name은 대소문자 구분 collation(`utf8mb4_0900_bin`)이라
 * 정확히 같은 값만 찾는다.
 */
@Component
class JooqAdminCredentialReader(private val dsl: DSLContext) : AdminCredentialReader {
    override fun findByLoginName(loginName: String): StoredCredential? {
        val t = ADMIN_ACCOUNT
        return dsl.select(t.ACTOR_ID, t.PASSWORD_HASH, t.SECURITY_VERSION).from(t).where(t.LOGIN_NAME.eq(loginName)).fetchOne { r ->
            StoredCredential(AdminActorId(r.get(t.ACTOR_ID)!!), r.get(t.PASSWORD_HASH)?.let(::PasswordHash), r.get(t.SECURITY_VERSION)!!)
        }
    }
}

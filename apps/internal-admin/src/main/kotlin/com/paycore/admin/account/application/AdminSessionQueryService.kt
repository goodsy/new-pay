package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.account.application.required.AdminSecuritySettings
import com.paycore.admin.account.application.required.AdminSessionReader
import com.paycore.admin.account.application.required.DemoQuickLoginSettings
import com.paycore.admin.account.domain.AdminActorId
import org.springframework.stereotype.Service

/** [AdminSessionQuery]: 요청마다 PK 단건 비잠금 읽기(jOOQ, TX 없음). 형식이 맞지 않는 actor ID는 없는 계정과 같다. */
@Service
class AdminSessionQueryService(
    private val reader: AdminSessionReader,
    private val settings: AdminSecuritySettings,
    private val demoQuickLogin: DemoQuickLoginSettings,
) : AdminSessionQuery {

    override fun current(actorId: String): AdminSessionView? {
        val id = runCatching { AdminActorId(actorId) }.getOrNull() ?: return null
        return reader.find(id)?.let {
            AdminSessionView(it.actorId.value, it.displayName, it.status.name, it.sessionGeneration, it.securityVersion, it.activeRoles.map { r -> r.name }.toSortedSet())
        }
    }

    override fun policy(): AdminSessionPolicy =
        AdminSessionPolicy(settings.anonymousSessionIdle, settings.passwordStepIdle, settings.authenticatedSessionIdle, settings.absoluteSessionLimit)

    override fun demoQuickLoginActive(): Boolean = demoQuickLogin.active
}

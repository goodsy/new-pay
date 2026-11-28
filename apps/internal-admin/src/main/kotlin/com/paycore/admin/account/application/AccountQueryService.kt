package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AccountListPage
import com.paycore.admin.account.application.provided.AccountListView
import com.paycore.admin.account.application.provided.AccountQuery
import com.paycore.admin.account.application.provided.ActiveOperatorPage
import com.paycore.admin.account.application.provided.ActiveOperatorQuery
import com.paycore.admin.account.application.provided.ActiveOperatorView
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.required.AdminAccountListReader
import com.paycore.admin.account.application.provided.ReactivationCauseView
import com.paycore.admin.account.application.required.AdminRoleEventReader
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.RoleSuspensions
import org.springframework.stereotype.Service

/** [AccountQuery] — 권한은 계정 현재 행, 조회는 jOOQ Reader(TX 없음, 쓰기 없음). */
@Service
class AccountQueryService(
    private val sessions: AdminSessionQuery,
    private val reader: AdminAccountListReader,
    private val roleEvents: AdminRoleEventReader,
) : AccountQuery, ActiveOperatorQuery {

    /** FLOW-2d(D-GOV-024): 활성 OPERATOR 표시명 목록 — 한 행 더 읽어 잘림을 안다. 로그인 이름은 읽지 않는다. */
    override fun activeOperators(): ActiveOperatorPage {
        val rows = reader.activeRoleHolders("OPERATOR", ActiveOperatorPage.LIMIT + 1)
        return ActiveOperatorPage(rows.take(ActiveOperatorPage.LIMIT).map { ActiveOperatorView(it.actorId, it.displayName) }, truncated = rows.size > ActiveOperatorPage.LIMIT)
    }

    override fun list(viewerActorId: String): AccountListPage? {
        val viewer = sessions.current(viewerActorId) ?: return null
        if (!viewer.isActive || viewer.roles.none { it in VIEW_ROLES }) return null
        // D-ADMIN1-017: 로그인 이름은 SECURITY_ADMIN 조회자에게만 — 그 밖 역할은 열 자체를 읽지 않는다.
        // 한 행 더 읽어 잘림을 안다(S8 리뷰 nit-5).
        val rows = reader.list(AccountListPage.LIMIT + 1, includeLoginName = "SECURITY_ADMIN" in viewer.roles)
        return AccountListPage(
            rows.take(AccountListPage.LIMIT).map { AccountListView(it.actorId, it.loginName, it.displayName, it.status, it.activeRoles, it.suspendedRoles, it.pendingPurpose) },
            truncated = rows.size > AccountListPage.LIMIT,
        )
    }

    override fun reactivationCauses(viewerActorId: String, targetActorId: String): List<ReactivationCauseView>? {
        val viewer = sessions.current(viewerActorId) ?: return null
        if (!viewer.isActive || REQUESTER_ROLE !in viewer.roles) return null
        val target = runCatching { AdminActorId(targetActorId) }.getOrNull() ?: return emptyList()
        return RoleSuspensions.byCause(roleEvents.changes(target)).map { (cause, roles) -> ReactivationCauseView(cause, roles.map { it.name }) }
    }

    private companion object {
        const val REQUESTER_ROLE = "SECURITY_ADMIN"
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
    }
}

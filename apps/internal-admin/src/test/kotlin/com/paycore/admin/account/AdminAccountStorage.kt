package com.paycore.admin.account

import com.paycore.admin.account.application.required.AdminAccountRepository
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.application.required.EnrollmentCodeGenerator
import com.paycore.admin.account.application.required.EnrollmentCodeStore
import com.paycore.admin.account.domain.AccountChange
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCode
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.RoleEvent
import com.paycore.admin.account.domain.RoleEventActor
import com.paycore.admin.account.domain.RoleEventType
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * DB 시험 준비(Named Fixture의 저장 측). 계정은 [AdminAccountFixture]의 domain 공식 전이로 만들고 저장 Port로 넣는다.
 * 재설정 대기·역할 부여는 실제 2인 승인 경로([com.paycore.admin.approval.ApprovalPath])로 만든다(S5 — 우회 준비 교체).
 */
@Component
class AdminAccountStorage(
    private val accounts: AdminAccountRepository,
    private val roleEvents: AdminRoleEventStore,
    private val codes: EnrollmentCodeStore,
    private val codeGenerator: EnrollmentCodeGenerator,
    transactionManager: PlatformTransactionManager,
) {
    private val tx = TransactionTemplate(transactionManager)

    fun <T> inTx(block: () -> T): T = tx.execute { block() }

    fun save(account: AdminAccount): AdminAccount = inTx { accounts.insert(account).value }

    fun securityAdmin(): AdminAccount = bootstrapped(AdminRole.SECURITY_ADMIN)

    /** 부트스트랩 경로 계정(단일 SA·APPROVER, GRANT 주체 = SystemBootstrap)과 그 GRANT 역할 이벤트를 같은 TX로 저장한다(역할 이벤트 재생 = 현재 역할). */
    fun bootstrapped(role: AdminRole): AdminAccount = saveWithGrants(AdminAccountFixture.bootstrapped(role), RoleEventActor.SystemBootstrap, null, listOf(role))

    private fun saveWithGrants(account: AdminAccount, actor: RoleEventActor, approvalId: String?, roles: List<AdminRole>): AdminAccount = inTx {
        val saved = accounts.insert(account).value
        roleEvents.append(roles.map { RoleEvent(account.id, it, RoleEventType.GRANT, actor, approvalId, null, AdminAccountFixture.NOW) })
        saved
    }

    fun load(id: AdminActorId): AdminAccount = inTx { accounts.find(id)!!.value }

    fun code(hash: com.paycore.admin.account.domain.EnrollmentCodeHash): EnrollmentCode? = inTx { codes.find(hash) }
}

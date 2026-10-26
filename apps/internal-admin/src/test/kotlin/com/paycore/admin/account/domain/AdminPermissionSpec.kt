package com.paycore.admin.account.domain

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountFixture.NOW
import com.paycore.admin.account.domain.AdminAction.CREATE_ACCOUNT
import com.paycore.admin.account.domain.AdminAction.DECIDE_APPROVAL
import com.paycore.admin.account.domain.AdminAction.REISSUE_ENROLL_CODE
import com.paycore.admin.account.domain.AdminAction.REQUEST_ADMIN_OWNED_COMMAND
import com.paycore.admin.account.domain.AdminAction.REQUEST_OPERATION
import com.paycore.admin.account.domain.AdminAction.REVOKE_ROLE
import com.paycore.admin.account.domain.AdminAction.SUSPEND_ACCOUNT
import com.paycore.admin.account.domain.AdminAction.UNMASK
import com.paycore.admin.account.domain.AdminAction.VIEW_ALL_AUDIT
import com.paycore.admin.account.domain.AdminAction.VIEW_MASKED
import com.paycore.admin.account.domain.AdminAction.VIEW_OWN_AUDIT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 권한 표(§4.5.1 — D-ADMIN-002, D-ADMIN1-004·008)를 역할별 **허용 집합 전체**로 고정한다(새 행위가 추가되면 이 표를 갱신해야 실패가 사라진다).
 * ACTIVE 계정만 권한을 갖는다(계정 행의 현재 활성 역할 — D-08).
 */
class AdminPermissionSpec : BehaviorSpec({

    fun allowed(account: AdminAccount) = AdminAction.entries.filter { AdminPermission.allows(account, it) }.toSet()

    val table = mapOf(
        AdminRole.VIEWER to setOf(VIEW_MASKED, VIEW_OWN_AUDIT),
        AdminRole.OPERATOR to setOf(VIEW_MASKED, VIEW_OWN_AUDIT, REQUEST_OPERATION),
        AdminRole.APPROVER to setOf(VIEW_MASKED, VIEW_OWN_AUDIT, VIEW_ALL_AUDIT, DECIDE_APPROVAL),
        AdminRole.SECURITY_ADMIN to setOf(
            VIEW_MASKED, VIEW_OWN_AUDIT, VIEW_ALL_AUDIT, CREATE_ACCOUNT, REISSUE_ENROLL_CODE, SUSPEND_ACCOUNT, REVOKE_ROLE, REQUEST_ADMIN_OWNED_COMMAND,
        ),
        AdminRole.UNMASK to setOf(UNMASK),
    )

    Given("one active role") {
        AdminRole.entries.forEach { role ->
            Then("$role allows exactly its row of the permission table") {
                allowed(AdminAccountFixture.withRolesViaApproval(role)) shouldBe table.getValue(role)
            }
        }
    }

    Given("combined roles (concurrent duties allowed)") {
        Then("the permissions are the union of the rows; UNMASK stays independent of the other roles") {
            allowed(AdminAccountFixture.withRolesViaApproval(AdminRole.OPERATOR, AdminRole.APPROVER)) shouldBe table.getValue(AdminRole.OPERATOR) + table.getValue(AdminRole.APPROVER)
            (UNMASK in allowed(AdminAccountFixture.withRolesViaApproval(*AdminRole.entries.filter { it != AdminRole.UNMASK }.toTypedArray()))) shouldBe false
        }
    }

    Given("accounts that are not ACTIVE") {
        Then("LOCKED, SUSPENDED and PENDING_ENROLLMENT accounts have no permission even with roles") {
            val admin = AdminAccountFixture.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.APPROVER)
            val locked = (admin.recordAuthenticationFailure(LoginAttemptPolicy(1), NOW) as AuthenticationFailure.Locked).account
            val suspended = AdminAccountFixture.applied(admin.suspend(AdminAccountFixture.newActorId(), NOW))
            val bootstrapPending = AdminAccountFixture.applied(AdminAccountFixture.pending().grantRole(AdminRole.SECURITY_ADMIN, RoleEventActor.SystemBootstrap, null, NOW))
            listOf(locked, suspended, bootstrapPending).map { allowed(it) } shouldBe List(3) { emptySet<AdminAction>() }
        }
        Then("suspended roles grant nothing after re-enrollment (they need a separate reactivation)") {
            val admin = AdminAccountFixture.bootstrapped(AdminRole.SECURITY_ADMIN)
            val reset = AdminAccountFixture.applied(admin.beginCredentialReset(EnrollmentPurpose.PASSWORD_RESET, "apr_PERM", AdminAccountFixture.APPROVAL_REQUESTER, AdminAccountFixture.newActorId(), NOW))
            val reenrolled = AdminAccountFixture.applied(reset.completeEnrollment(reset.pending!!, AdminAccountFixture.OTHER_PASSWORD_HASH, null, NOW))
            (reenrolled.status to allowed(reenrolled)) shouldBe (AccountStatus.ACTIVE to emptySet())
        }
    }
})

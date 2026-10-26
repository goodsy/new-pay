package com.paycore.admin.account.application

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.CreateAdminAccount
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.account.domain.EnrollmentPurpose
import com.paycore.admin.account.domain.RoleEventType
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.column
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID

/**
 * SECURITY_ADMIN 1인 즉시 명령(D-ADMIN1-003·004, §4.7.1, rev 0.3 H-B·n-E, rev 0.4 H-1). 실제 MySQL, runtime 계정 권한 안. 각 명령은 상태·역할 이벤트·코드·감사를
 * 한 로컬 TX로 커밋하고, 거부·거절도 감사 1행을 남긴다. 등록 코드 원문은 결과에만(감사·DB에 없음).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AdminAccountCommandSpec(
    private val commands: AdminAccountCommandUseCase,
    private val storage: AdminAccountStorage,
    private val approvalPath: ApprovalPath,
    private val roleEvents: AdminRoleEventStore,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun context() = AdminRequestContext("corr-" + UUID.randomUUID().toString().take(8), "10.0.0.1")
    fun uniqueLogin() = "ops-" + UUID.randomUUID().toString().take(8)
    fun auditRows(correlationId: String): List<String> = com.paycore.admin.support.AdminDbAccounts.root(container).use { c ->
        c.column(
            "SELECT CONCAT(action, ':', result_code, ':', COALESCE(actor_id, '-'), ':', COALESCE(target_actor_id, '-'), ':', COALESCE(detail, '-')) " +
                "FROM admin_audit_event WHERE correlation_id = ? ORDER BY event_id",
            correlationId,
        )
    }

    Given("account creation") {
        Then("a SECURITY_ADMIN creates a role-less PENDING_ENROLLMENT account with a one-time ENROLL code (only in the result) and audits it") {
            val admin = storage.securityAdmin()
            val ctx = context()
            val result = commands.createAccount(CreateAdminAccount(admin.id.value, uniqueLogin(), "운영자", ctx)).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            val created = storage.load(AdminActorId(result.targetActorId))
            val code = requireNotNull(result.enrollmentCode)
            listOf(created.status, created.activeRoles, created.pending?.purpose) shouldBe listOf(AccountStatus.PENDING_ENROLLMENT, emptySet<AdminRole>(), EnrollmentPurpose.ENROLL)
            val row = storage.code(EnrollmentCodeHash.of(code))!!
            (row.target to row.pending) shouldBe (created.id to created.pending)
            val audit = auditRows(ctx.correlationId)
            audit shouldBe listOf(
                "ACCOUNT_CREATED:SUCCESS:${admin.id.value}:${created.id.value}:-",
                "ENROLLMENT_CODE_ISSUED:SUCCESS:${admin.id.value}:${created.id.value}:purpose=ENROLL",
            )
            audit.none { it.contains(code) } shouldBe true
        }
        Then("a requester without SECURITY_ADMIN (role-less or suspended) is denied; nothing is created; the denial is audited") {
            val roleless = storage.save(AdminAccountFixture.enrolledActive())
            val ctx = context()
            val login = uniqueLogin()
            commands.createAccount(CreateAdminAccount(roleless.id.value, login, "운영자", ctx)) shouldBe AccountCommandResult.Denied
            auditRows(ctx.correlationId) shouldBe listOf("ACCOUNT_CREATED:DENIED:${roleless.id.value}:-:-")
            val admin = storage.securityAdmin()
            val other = storage.securityAdmin()
            commands.suspend(TargetedAccountCommand(other.id.value, admin.id.value, context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            commands.createAccount(CreateAdminAccount(admin.id.value, uniqueLogin(), "운영자", context())) shouldBe AccountCommandResult.Denied
        }
        Then("a taken login name or an invalid input is rejected with a fixed code") {
            val admin = storage.securityAdmin()
            val login = uniqueLogin()
            commands.createAccount(CreateAdminAccount(admin.id.value, login, "A", context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            listOf(
                commands.createAccount(CreateAdminAccount(admin.id.value, login, "B", context())),
                commands.createAccount(CreateAdminAccount(admin.id.value, "Bad Name", "B", context())),
                commands.createAccount(CreateAdminAccount("not-an-actor-id", uniqueLogin(), "B", context())),
            ) shouldBe listOf(AccountCommandResult.Rejected("LOGIN_NAME_TAKEN"), AccountCommandResult.Rejected("INVALID_INPUT"), AccountCommandResult.Rejected("INVALID_INPUT"))
        }
    }

    Given("suspension") {
        Then("the target is suspended, its unused enrollment codes are revoked (H-B) and the action is audited") {
            val admin = storage.securityAdmin()
            val created = commands.createAccount(CreateAdminAccount(admin.id.value, uniqueLogin(), "대상", context())) as AccountCommandResult.Succeeded
            val ctx = context()
            commands.suspend(TargetedAccountCommand(admin.id.value, created.targetActorId, ctx)) shouldBe AccountCommandResult.Succeeded(created.targetActorId)
            val target = storage.load(AdminActorId(created.targetActorId))
            (target.status to target.pending) shouldBe (AccountStatus.SUSPENDED to null)
            storage.code(EnrollmentCodeHash.of(created.enrollmentCode!!))!!.revokedAt shouldNotBe null
            val basis = storage.code(EnrollmentCodeHash.of(created.enrollmentCode))!!.basis
            auditRows(ctx.correlationId) shouldBe listOf("ACCOUNT_SUSPENDED:SUCCESS:${admin.id.value}:${created.targetActorId}:revokedCodes=1;revokedBases=$basis")
        }
        Then("suspending oneself or an unknown target is rejected without change") {
            val admin = storage.securityAdmin()
            listOf(
                commands.suspend(TargetedAccountCommand(admin.id.value, admin.id.value, context())),
                commands.suspend(TargetedAccountCommand(admin.id.value, AdminAccountFixture.newActorId().value, context())),
            ) shouldBe listOf(AccountCommandResult.Rejected("SELF_SUSPEND"), AccountCommandResult.Rejected("TARGET_NOT_FOUND"))
            storage.load(admin.id).status shouldBe AccountStatus.ACTIVE
        }
    }

    Given("role revocation") {
        Then("an active role is revoked with a REVOKE event in the same transaction; sessions end") {
            val admin = storage.securityAdmin()
            val target = approvalPath.withRolesViaApproval(AdminRole.APPROVER, AdminRole.SECURITY_ADMIN)
            commands.revokeRole(RevokeAdminRole(admin.id.value, target.id.value, "APPROVER", context())) shouldBe AccountCommandResult.Succeeded(target.id.value)
            val after = storage.load(target.id)
            (after.activeRoles to after.sessionGeneration) shouldBe (setOf(AdminRole.SECURITY_ADMIN) to (target.sessionGeneration + 1))
            storage.inTx { roleEvents.history(target.id) }.last().let { it.role to it.type } shouldBe (AdminRole.APPROVER to RoleEventType.REVOKE)
        }
        Then("replaying the stored role events reproduces the account's current active and suspended roles (RoleStateConsistency)") {
            val admin = storage.securityAdmin()
            val (reset, _) = approvalPath.credentialReset("PASSWORD_RESET", approvalPath.withRolesViaApproval(AdminRole.APPROVER, AdminRole.OPERATOR, AdminRole.VIEWER).id)
            commands.revokeRole(RevokeAdminRole(admin.id.value, reset.id.value, "OPERATOR", context())) shouldBe AccountCommandResult.Succeeded(reset.id.value)
            val active = mutableSetOf<AdminRole>()
            val suspended = mutableSetOf<AdminRole>()
            storage.inTx { roleEvents.history(reset.id) }.forEach { e ->
                when (e.type) {
                    RoleEventType.GRANT -> active += e.role
                    RoleEventType.REVOKE -> { active -= e.role; suspended -= e.role }
                    RoleEventType.SUSPEND -> { active -= e.role; suspended += e.role }
                    RoleEventType.REACTIVATE -> { suspended -= e.role; active += e.role }
                }
            }
            val current = storage.load(reset.id)
            (active to suspended) shouldBe (current.activeRoles to current.suspendedRoles)
            current.suspendedRoles shouldBe setOf(AdminRole.APPROVER, AdminRole.VIEWER)
        }
        Then("revoking one's own role, a role not held or an unknown role is rejected") {
            val admin = storage.securityAdmin()
            val target = storage.bootstrapped(AdminRole.APPROVER)
            listOf(
                commands.revokeRole(RevokeAdminRole(admin.id.value, admin.id.value, "SECURITY_ADMIN", context())),
                commands.revokeRole(RevokeAdminRole(admin.id.value, target.id.value, "VIEWER", context())),
                commands.revokeRole(RevokeAdminRole(admin.id.value, target.id.value, "ROOT", context())),
            ) shouldBe listOf(AccountCommandResult.Rejected("SELF_ROLE_CHANGE"), AccountCommandResult.Rejected("ROLE_NOT_HELD"), AccountCommandResult.Rejected("INVALID_INPUT"))
        }
    }

    Given("ENROLL code reissue (rev 0.4 H-1)") {
        Then("a role-less pending account gets a new code; the previous code is revoked and the basis replaced") {
            val admin = storage.securityAdmin()
            val created = commands.createAccount(CreateAdminAccount(admin.id.value, uniqueLogin(), "대상", context())) as AccountCommandResult.Succeeded
            val reissued = commands.reissueEnrollCode(TargetedAccountCommand(admin.id.value, created.targetActorId, context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            val newCode = storage.code(EnrollmentCodeHash.of(reissued.enrollmentCode!!))!!
            storage.code(EnrollmentCodeHash.of(created.enrollmentCode!!))!!.revokedAt shouldNotBe null
            storage.load(AdminActorId(created.targetActorId)).pending shouldBe newCode.pending
        }
        Then("a role-bearing pending account (bootstrap pair) is refused — only a 2-person reset or recovery can reissue") {
            val admin = storage.securityAdmin()
            val bootstrapPending = storage.save(
                AdminAccountFixture.applied(
                    AdminAccountFixture.pending().grantRole(AdminRole.APPROVER, com.paycore.admin.account.domain.RoleEventActor.SystemBootstrap, null, AdminAccountFixture.NOW),
                ),
            )
            commands.reissueEnrollCode(TargetedAccountCommand(admin.id.value, bootstrapPending.id.value, context())) shouldBe AccountCommandResult.Rejected("ROLE_BEARING_ACCOUNT")
        }
        Then("a credential-reset account holding only suspended roles is refused: security_version, pending reason and code row unchanged; one REJECTED audit row") {
            val admin = storage.securityAdmin()
            val (reset, rawCode) = approvalPath.credentialReset("PASSWORD_RESET", storage.bootstrapped(AdminRole.APPROVER).id)
            val codeBefore = storage.code(EnrollmentCodeHash.of(rawCode))!!
            val ctx = context()
            commands.reissueEnrollCode(TargetedAccountCommand(admin.id.value, reset.id.value, ctx)) shouldBe AccountCommandResult.Rejected("ROLE_BEARING_ACCOUNT")
            val after = storage.load(reset.id)
            listOf(after.securityVersion, after.pending, after.suspendedRoles) shouldBe listOf(reset.securityVersion, reset.pending, setOf(AdminRole.APPROVER))
            storage.code(EnrollmentCodeHash.of(rawCode)) shouldBe codeBefore
            auditRows(ctx.correlationId) shouldBe listOf("ENROLLMENT_CODE_ISSUED:REJECTED:${admin.id.value}:${reset.id.value}:reason=ROLE_BEARING_ACCOUNT")
        }
    }

    Given("lock order and permission under the row lock (S3 review M-3)") {
        fun deadlocks(): Long = com.paycore.admin.support.AdminDbAccounts.root(container, "information_schema").use { c ->
            c.createStatement().use { it.execute("SET GLOBAL innodb_monitor_enable = 'lock_deadlocks'") }
            c.createStatement().use { st -> st.executeQuery("SELECT COUNT FROM INNODB_METRICS WHERE NAME = 'lock_deadlocks'").use { it.next(); it.getLong(1) } }
        }
        Then("two SECURITY_ADMINs acting on each other in reverse actor order never deadlock (requester/target locked in ascending actor ID — §4.13)") {
            val before = deadlocks()
            repeat(4) {
                val a = approvalPath.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.VIEWER)
                val b = approvalPath.withRolesViaApproval(AdminRole.SECURITY_ADMIN, AdminRole.VIEWER)
                val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                val results = RowLockHolder.open(container).use { holder ->
                    holder.lock("admin_account", "actor_id", a.id.value)
                    val first = pool.submit<AccountCommandResult> { commands.revokeRole(RevokeAdminRole(a.id.value, b.id.value, "VIEWER", context())) }
                    val second = pool.submit<AccountCommandResult> { commands.revokeRole(RevokeAdminRole(b.id.value, a.id.value, "VIEWER", context())) }
                    holder.awaitWaiters("admin_account", 2)
                    // S4 리뷰 m-7: 기다리는 두 TX 모두 다른 계정 행 잠금을 쥐고 있지 않다(오름차순 — 둘 다 낮은 actor 행에서 대기). 순서가 깨지면 1회 실행으로 실패.
                    holder.grantedRowLocksOfWaiters("admin_account") shouldBe 0
                    holder.release()
                    listOf(first, second).map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                }
                pool.shutdown()
                results shouldBe listOf(AccountCommandResult.Succeeded(b.id.value), AccountCommandResult.Succeeded(a.id.value))
            }
            (deadlocks() - before) shouldBe 0L
        }
        Then("the requester's permission is judged on the locked (latest committed) row: a suspension committed while the request waited makes it DENIED") {
            val admin = storage.securityAdmin()
            val target = storage.save(AdminAccountFixture.pending())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(1)
            val result = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", admin.id.value)
                val suspension = pool.submit<AccountCommandResult> { commands.suspend(TargetedAccountCommand(admin.id.value, target.id.value, context())) }
                holder.awaitWaiters("admin_account", 1)
                // 다른 SECURITY_ADMIN의 정지가 이 요청이 기다리는 동안 커밋된 상황(세 번째 연결이 같은 행을 정지 상태로 커밋).
                holder.update(
                    "UPDATE admin_account SET status = 'SUSPENDED', status_before_suspend = 'ACTIVE', session_generation = session_generation + 1, " +
                        "security_version = security_version + 1, version = version + 1 WHERE actor_id = ?",
                    admin.id.value,
                )
                holder.release(commit = true)
                suspension.get(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            pool.shutdown()
            result shouldBe AccountCommandResult.Denied
            storage.load(target.id).status shouldBe AccountStatus.PENDING_ENROLLMENT
        }
    }
})

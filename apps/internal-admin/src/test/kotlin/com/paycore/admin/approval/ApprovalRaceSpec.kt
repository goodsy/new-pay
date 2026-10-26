package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 승인 경합(§4.7.2·§4.7.3·§4.7.4 — `DuplicatePendingRaceSpec`·"같은 대상·다른 종류 동시 생성 1213 0회"·`ApprovalVersusCloseRaceSpec`·`RevokeDuringApprovalRaceSpec`·
 * `StepUpTotpReuseRaceSpec`). 세 번째 연결([RowLockHolder])로 행을 잡고, 경합 TX가 **실제로 기다리는 것을 확인한 뒤** 놓는다(우연한 스케줄에 기대지 않음).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class ApprovalRaceSpec(
    private val approvals: ApprovalUseCase,
    private val path: ApprovalPath,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val commands: AdminAccountCommandUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    fun gen(id: AdminActorId) = storage.load(id).sessionGeneration
    fun requestOf(requester: AdminAccount, kind: String, target: AdminActorId, role: String? = null) =
        RequestApproval(requester.id.value, gen(requester.id), kind, target.value, role, null, path.context())
    fun decide(id: String, approver: LoginReadyAccounts.LoginReady, code: String = TestTotp.code(approver.secret)) =
        DecideApproval(id, approver.account.id.value, gen(approver.account.id), code, path.context())
    fun statusOf(id: String): String = AdminDbAccounts.root(container).use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun deadlocks(): Long = AdminDbAccounts.root(container, "information_schema").use { c ->
        c.createStatement().use { it.execute("SET GLOBAL innodb_monitor_enable = 'lock_deadlocks'") }
        c.createStatement().use { st -> st.executeQuery("SELECT COUNT FROM INNODB_METRICS WHERE NAME = 'lock_deadlocks'").use { it.next(); it.getLong(1) } }
    }
    fun <T> pool(vararg tasks: Callable<T>, block: (List<java.util.concurrent.Future<T>>) -> Unit): List<T> {
        val executor = Executors.newFixedThreadPool(tasks.size)
        try {
            val futures = tasks.map { executor.submit(it) }
            block(futures)
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }
    }

    Given("concurrent creation") {
        Then("same kind and target from two requesters (both proven to overlap): exactly one is created, the other DUPLICATE_PENDING; one PENDING row; audits 1 success + 1 denial") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val (r1, r2) = storage.securityAdmin() to storage.securityAdmin()
            val secondResult = java.util.concurrent.atomic.AtomicReference<ApprovalOutcome>()
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", target.id.value)
                pool(Callable { approvals.request(requestOf(r1, "ROLE_GRANT", target.id, "VIEWER")) }) { first ->
                    holder.awaitWaiters("admin_account", 1)
                    val second = Executors.newSingleThreadExecutor().let { ex -> ex.submit(Callable { approvals.request(requestOf(r2, "ROLE_GRANT", target.id, "VIEWER")) }).also { ex.shutdown() } }
                    holder.awaitWaiters("admin_approval_request", 1, blockedByHolder = false)
                    holder.release()
                    first.single().get(60, TimeUnit.SECONDS)
                    secondResult.set(second.get(60, TimeUnit.SECONDS))
                } + listOf(secondResult.get())
            }
            results.count { it is ApprovalOutcome.Created } shouldBe 1
            results.count { it == ApprovalOutcome.Rejected("DUPLICATE_PENDING") } shouldBe 1
            AdminDbAccounts.root(container).use {
                (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_actor_id = ? AND status = 'PENDING'", target.id.value) as Number).toInt() shouldBe 1
                it.column("SELECT CONCAT(action, ':', result_code) FROM admin_audit_event WHERE target_actor_id = ? AND action LIKE 'APPROVAL%' ORDER BY event_id", target.id.value).sorted() shouldBe
                    listOf("APPROVAL_DENIED:DENIED", "APPROVAL_REQUESTED:SUCCESS")
            }
        }
        Then("same target, different kinds, created concurrently: both wait on the target row holding no other account row; no deadlock (S2 review M-1)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val (r1, r2) = storage.securityAdmin() to storage.securityAdmin()
            val before = deadlocks()
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", target.id.value)
                pool(
                    Callable { approvals.request(requestOf(r1, "ROLE_GRANT", target.id, "VIEWER")) },
                    Callable { approvals.request(requestOf(r2, "PASSWORD_RESET", target.id)) },
                ) {
                    holder.awaitWaiters("admin_account", 2)
                    holder.grantedRowLocksOfWaiters("admin_account") shouldBe 0
                    holder.release()
                }
            }
            results.all { it is ApprovalOutcome.Created } shouldBe true
            (deadlocks() - before) shouldBe 0L
        }
    }

    Given("two approvers approving the same request at once (ApprovalConcurrency)") {
        Then("serialized by the request row: exactly one execution, the other ALREADY_DECIDED without consuming its TOTP step; one GRANT event, one execution audit") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val (a1, a2) = ready.bootstrapped(AdminRole.APPROVER) to ready.bootstrapped(AdminRole.APPROVER)
            val (d1, d2) = decide(id, a1) to decide(id, a2)
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_approval_request", "approval_id", id)
                pool(Callable { approvals.approve(d1) }, Callable { approvals.approve(d2) }) {
                    holder.awaitWaiters("admin_approval_request", 2)
                    holder.release()
                }
            }
            results.count { it == ApprovalOutcome.Executed(id) } shouldBe 1
            results.count { it == ApprovalOutcome.AlreadyDecided } shouldBe 1
            val loser = if (results[0] == ApprovalOutcome.AlreadyDecided) a1 else a2
            storage.load(loser.account.id).lastTotpStep shouldBe 0L
            storage.load(target.id).activeRoles shouldBe setOf(AdminRole.VIEWER)
            AdminDbAccounts.root(container).use {
                (it.scalar("SELECT COUNT(*) FROM admin_role_event WHERE target_actor_id = ? AND event_type = 'GRANT'", target.id.value) as Number).toInt() shouldBe 1
                it.column("SELECT action FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_EXECUTED'", id) shouldBe listOf("APPROVAL_EXECUTED")
            }
        }
    }

    Given("approval versus closing commands on the same PENDING request (ApprovalVersusCloseRace)") {
        Then("approve vs withdraw / approve vs reject / approve vs expiry: serialized by the request row — exactly one terminal state, the other ALREADY_DECIDED; the account changes only if approval won") {
            listOf("withdraw", "reject", "expire").forEach { closer ->
                val target = storage.save(AdminAccountFixture.enrolledActive())
                val requester = storage.securityAdmin()
                val id = (approvals.request(requestOf(requester, "ROLE_GRANT", target.id, "VIEWER")) as ApprovalOutcome.Created).approvalId
                val approver = ready.bootstrapped(AdminRole.APPROVER)
                val rejecter = ready.bootstrapped(AdminRole.APPROVER)
                if (closer == "expire") {
                    AdminDbAccounts.root(container).use { it.update("UPDATE admin_approval_request SET expires_at = requested_at + INTERVAL 1000 MICROSECOND WHERE approval_id = ?", id) }
                }
                val results = RowLockHolder.open(container).use { holder ->
                    holder.lock("admin_approval_request", "approval_id", id)
                    pool(
                        Callable<Any> { approvals.approve(decide(id, approver)) },
                        Callable<Any> {
                            when (closer) {
                                "withdraw" -> approvals.withdraw(WithdrawApproval(id, requester.id.value, gen(requester.id), path.context()))
                                "reject" -> approvals.reject(decide(id, rejecter))
                                else -> approvals.expireDue()
                            }
                        },
                    ) {
                        holder.awaitWaiters("admin_approval_request", 2)
                        holder.release()
                    }
                }
                val status = statusOf(id)
                val roles = storage.load(target.id).activeRoles
                when (closer) {
                    "expire" -> {
                        // 두 결과만 허용(S5 리뷰 m-2): 승인 TX가 먼저 잠그면 그 TX가 만료를 판정·기록(Expired, job 0건), job이 먼저면 job이 기록(승인은 ALREADY_DECIDED, job 1건).
                        (results[0] to results[1]) shouldBeIn listOf<Pair<Any, Any>>(ApprovalOutcome.Expired to 0, ApprovalOutcome.AlreadyDecided to 1)
                        (status to roles) shouldBe ("EXPIRED" to emptySet())
                        AdminDbAccounts.root(container).use {
                            it.column("SELECT action FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_EXPIRED'", id)
                        } shouldBe listOf("APPROVAL_EXPIRED")
                    }
                    else -> if (results[0] is ApprovalOutcome.Executed) {
                        (status to roles) shouldBe ("EXECUTED" to setOf(AdminRole.VIEWER))
                        results[1] shouldBe ApprovalOutcome.AlreadyDecided
                    } else {
                        results[0] shouldBe ApprovalOutcome.AlreadyDecided
                        (status in setOf("WITHDRAWN", "REJECTED") && roles.isEmpty()) shouldBe true
                    }
                }
            }
        }
    }

    Given("revoking the approver's APPROVER role during an approval (RevokeDuringApprovalRace)") {
        Then("revocation committed first (while the approval waits on the request row) → the approval is denied with no change") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val admin = storage.securityAdmin()
            val decision = decide(id, approver)
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_approval_request", "approval_id", id)
                pool(Callable { approvals.approve(decision) }) {
                    holder.awaitWaiters("admin_approval_request", 1)
                    commands.revokeRole(RevokeAdminRole(admin.id.value, approver.account.id.value, "APPROVER", path.context())) shouldBe
                        AccountCommandResult.Succeeded(approver.account.id.value)
                    holder.release()
                }
            }
            results.single() shouldBe ApprovalOutcome.Denied
            (statusOf(id) to storage.load(target.id).activeRoles) shouldBe ("PENDING" to emptySet())
        }
        Then("approval holding the approver row first (revocation proven waiting on it) → executed once; the revocation then commits and ends the approver's sessions") {
            // ID 순서: 요청자 < 승인자 < 대상 < 회수자. 승인 TX는 요청자·승인자 행을 잡은 채 대상 행(세 번째 연결)을 기다리고, 회수 TX는 승인자 행에서 기다린다.
            val requester = storage.securityAdmin()
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = (approvals.request(requestOf(requester, "ROLE_GRANT", target.id, "VIEWER")) as ApprovalOutcome.Created).approvalId
            val admin = storage.securityAdmin()
            val decision = decide(id, approver)
            val before = gen(approver.account.id)
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", target.id.value)
                pool(
                    Callable<Any> { approvals.approve(decision) },
                    Callable<Any> {
                        holder.awaitWaiters("admin_account", 1)
                        commands.revokeRole(RevokeAdminRole(admin.id.value, approver.account.id.value, "APPROVER", path.context()))
                    },
                ) {
                    holder.awaitWaiters("admin_account", 2, blockedByHolder = false)
                    holder.release()
                }
            }
            results shouldBe listOf(ApprovalOutcome.Executed(id), AccountCommandResult.Succeeded(approver.account.id.value))
            (storage.load(target.id).activeRoles to storage.load(approver.account.id).activeRoles) shouldBe (setOf(AdminRole.VIEWER) to emptySet())
            gen(approver.account.id) shouldBe before + 1
        }
    }

    Given("one approver, one TOTP code, two requests at once (StepUpTotpReuseRace)") {
        Then("serialized by the approver row: one approval executes, the other is a step reuse (StepUpFailed + one counted failure)") {
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val ids = List(2) { path.request("ROLE_GRANT", storage.save(AdminAccountFixture.enrolledActive()).id, "VIEWER") }
            val code = TestTotp.code(approver.secret)
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", approver.account.id.value)
                pool(*ids.map { id -> Callable { approvals.approve(decide(id, approver, code)) } }.toTypedArray()) {
                    holder.awaitWaiters("admin_account", 2)
                    holder.release()
                }
            }
            results.count { it is ApprovalOutcome.Executed } shouldBe 1
            results.count { it == ApprovalOutcome.StepUpFailed } shouldBe 1
            storage.load(approver.account.id).failedAttempts shouldBe 1
        }
    }
})

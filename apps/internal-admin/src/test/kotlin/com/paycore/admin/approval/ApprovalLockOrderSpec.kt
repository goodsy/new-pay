package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.AdminLoginUseCase
import com.paycore.admin.account.application.provided.AdminRetryLaterException
import com.paycore.admin.account.application.provided.EnrollmentResult
import com.paycore.admin.account.application.provided.EnrollmentUseCase
import com.paycore.admin.account.application.provided.PasswordStepResult
import com.paycore.admin.account.application.provided.TargetedAccountCommand
import com.paycore.admin.account.domain.AccountStatus
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.EnrollmentCodeHash
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.testcontainers.mysql.MySQLContainer
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 전역 잠금 순서와 1213·1205(§4.13 — `ApprovalLockOrderSpec`, S4 리뷰 m-7 방식): ① 참여자가 겹치는 두 승인 — 둘 다 기다리는 동안 다른 계정 행 잠금을 쥐지 않음(data_locks)·
 * 교착 지표 0, ② 강제 교착(1213) — 승인·거절·철회 TX가 희생되면 전체 rollback 후 `RETRY_LATER`, 상태·TOTP 단계·감사 무변경, ③ 대기 초과(1205 — 이 컨텍스트만 세션
 * `innodb_lock_wait_timeout = 3`) — 승인·거절·철회 모두 `RETRY_LATER`·무변경, ④ (S4 이월 — `retryLaterOnConflict` 서비스 경계) 로그인 비밀번호·TOTP 단계·1인 명령(정지)·
 * 등록 완료 TX의 실제 1205 → 409 `RETRY_LATER`(HTTP 포함)·무변경, 1인 명령의 실제 1213 → 전체 rollback 뒤 새 TX 재판정(D-69)으로 효과 1회.
 * 서비스 경계(실제 MySQL 오류 → Use Case 결과) 시험이다. 복구 명령 행은 S6(1회성 명령)에서 같은 방식으로 추가한다.
 */
@SpringBootTest(properties = ["spring.datasource.hikari.data-source-properties.sessionVariables=innodb_lock_wait_timeout=3"])
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class ApprovalLockOrderSpec(
    private val approvals: ApprovalUseCase,
    private val path: ApprovalPath,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val login: AdminLoginUseCase,
    private val commands: AdminAccountCommandUseCase,
    private val enrollment: EnrollmentUseCase,
    private val mockMvc: MockMvc,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    fun gen(id: AdminActorId) = storage.load(id).sessionGeneration
    fun decide(id: String, approver: LoginReadyAccounts.LoginReady) =
        DecideApproval(id, approver.account.id.value, gen(approver.account.id), TestTotp.code(approver.secret), path.context())
    fun statusOf(id: String): String = AdminDbAccounts.root(container).use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun approvalAudits(id: String): List<String> = AdminDbAccounts.root(container).use {
        it.column("SELECT action FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id)
    }
    fun auditsOf(actorOrTarget: AdminActorId): List<String> = AdminDbAccounts.root(container).use {
        it.column("SELECT CONCAT(action, ':', result_code) FROM admin_audit_event WHERE actor_id = ? OR target_actor_id = ? ORDER BY event_id", actorOrTarget.value, actorOrTarget.value)
    }
    fun deadlocks(): Long = AdminDbAccounts.root(container, "information_schema").use { c ->
        c.createStatement().use { it.execute("SET GLOBAL innodb_monitor_enable = 'lock_deadlocks'") }
        c.createStatement().use { st -> st.executeQuery("SELECT COUNT FROM INNODB_METRICS WHERE NAME = 'lock_deadlocks'").use { it.next(); it.getLong(1) } }
    }
    /** 세 번째 연결을 "무거운" TX로 만든다(행 50개 변경) — InnoDB가 가벼운 업무 TX를 교착 희생자로 고르게. 변경은 release(rollback)로 사라진다. */
    fun RowLockHolder.makeHeavy() = repeat(50) {
        update("INSERT INTO admin_audit_event (occurred_at, actor_kind, action, result_code, correlation_id) VALUES (UTC_TIMESTAMP(3), 'SYSTEM_JOB', 'TEST_WEIGHT', 'SUCCESS', 'weight')")
    }

    Given("two approvals whose participants overlap in reverse roles") {
        Then("both wait on the lowest shared account row without holding another account row; both execute; no deadlock") {
            val x = storage.securityAdmin()
            val y = storage.securityAdmin()
            val first = (approvals.request(RequestApproval(x.id.value, gen(x.id), "ROLE_GRANT", y.id.value, "VIEWER", null, path.context())) as ApprovalOutcome.Created).approvalId
            val second = (approvals.request(RequestApproval(y.id.value, gen(y.id), "ROLE_GRANT", x.id.value, "VIEWER", null, path.context())) as ApprovalOutcome.Created).approvalId
            val (p1, p2) = ready.bootstrapped(AdminRole.APPROVER) to ready.bootstrapped(AdminRole.APPROVER)
            val (d1, d2) = decide(first, p1) to decide(second, p2)
            val before = deadlocks()
            val pool = Executors.newFixedThreadPool(2)
            val results = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", x.id.value)
                val futures = listOf(pool.submit(Callable { approvals.approve(d1) }), pool.submit(Callable { approvals.approve(d2) }))
                holder.awaitWaiters("admin_account", 2)
                holder.grantedRowLocksOfWaiters("admin_account") shouldBe 0
                holder.release()
                futures.map { it.get(60, TimeUnit.SECONDS) }
            }
            pool.shutdown()
            results shouldBe listOf(ApprovalOutcome.Executed(first), ApprovalOutcome.Executed(second))
            (deadlocks() - before) shouldBe 0L
        }
    }

    Given("a forced deadlock with a heavier third transaction (1213) — approve, reject and withdraw (§4.7.8)") {
        Then("the decision TX (holding the request row, waiting on the requester row) is the victim: RETRY_LATER, PENDING, TOTP step unchanged, no decision audit") {
            listOf("approve", "reject", "withdraw").forEach { op ->
                val target = storage.save(AdminAccountFixture.enrolledActive())
                val requester = storage.securityAdmin()
                val id = (approvals.request(RequestApproval(requester.id.value, gen(requester.id), "ROLE_GRANT", target.id.value, "VIEWER", null, path.context())) as ApprovalOutcome.Created)
                    .approvalId
                val approver = ready.bootstrapped(AdminRole.APPROVER)
                val decision = decide(id, approver)
                val withdrawal = WithdrawApproval(id, requester.id.value, gen(requester.id), path.context())
                val before = deadlocks()
                val pool = Executors.newFixedThreadPool(2)
                val outcome = RowLockHolder.open(container).use { holder ->
                    holder.makeHeavy()
                    // 승인: 대상 → 요청자(대기), 거절: 요청자(대기) → 승인자, 철회: 요청자(대기) — 모두 승인 행을 쥔 채 요청자 계정 행을 기다린다.
                    holder.lock("admin_account", "actor_id", requester.id.value)
                    val decided = pool.submit(
                        Callable {
                            when (op) {
                                "approve" -> approvals.approve(decision)
                                "reject" -> approvals.reject(decision)
                                else -> approvals.withdraw(withdrawal)
                            }
                        },
                    )
                    holder.awaitWaiters("admin_account", 1)
                    // 세 번째 연결이 그 승인 행을 요구하면 순환 대기 → 교착.
                    val cycle = pool.submit(Callable { holder.lock("admin_approval_request", "approval_id", id) })
                    val result = decided.get(60, TimeUnit.SECONDS)
                    cycle.get(60, TimeUnit.SECONDS)
                    holder.release()
                    result
                }
                pool.shutdown()
                // Kotest 6.2.5: `(a to b) shouldBe (x to <data object>)`는 data class 필드 비교라 data object 쪽 필드가 없어 늘 통과한다(S7 수정 실측) — 결과는 직접 비교한다.
                withClue(op) { outcome shouldBe ApprovalOutcome.RetryLater }
                (deadlocks() - before) shouldBe 1L
                listOf(statusOf(id), storage.load(approver.account.id).lastTotpStep, approvalAudits(id), storage.load(target.id).activeRoles.size) shouldBe
                    listOf("PENDING", 0L, listOf("APPROVAL_REQUESTED"), 0)
            }
        }
    }

    Given("1205 / 1213 injected AFTER the approver's TOTP step was consumed (S5 review M-1 — the code row is held so PASSWORD_RESET blocks in revokeUnused)") {
        Then("the whole TX rolls back: RETRY_LATER, the TOTP step is not consumed, PENDING, no decision audit, the old code untouched; the same TOTP code then approves") {
            listOf("1205", "1213").forEach { mode ->
                val target = path.withRolesViaApproval(AdminRole.VIEWER)
                val (_, oldCode, _) = path.credentialReset("PASSWORD_RESET", target.id)
                val requester = storage.securityAdmin()
                val id = (approvals.request(RequestApproval(requester.id.value, gen(requester.id), "PASSWORD_RESET", target.id.value, null, null, path.context())) as ApprovalOutcome.Created)
                    .approvalId
                val approver = ready.bootstrapped(AdminRole.APPROVER)
                val stepBefore = storage.load(approver.account.id).lastTotpStep
                val decision = decide(id, approver)
                val before = deadlocks()
                fun counter(name: String, vararg tags: String) = registry.find(name).tags(*tags).counter()?.count() ?: 0.0
                val executedBefore = counter("paycore.admin.approval", "operation", "APPROVE", "kind", "PASSWORD_RESET", "result", "EXECUTED")
                val retryBefore = counter("paycore.admin.approval.retry_later", "operation", "APPROVE")
                val pool = Executors.newFixedThreadPool(2)
                val outcome = RowLockHolder.open(container).use { holder ->
                    if (mode == "1213") holder.makeHeavy()
                    holder.lock("admin_enrollment_code", "code_hash", EnrollmentCodeHash.of(oldCode).bytes)
                    val decided = pool.submit(Callable { approvals.approve(decision) })
                    holder.awaitWaiters("admin_enrollment_code", 1)
                    // 이 시점에 승인 TX는 승인 행·계정 행을 잠갔고 TOTP 단계를 소비했다(코드 폐기가 마지막 쓰기 앞 단계).
                    if (mode == "1213") pool.submit(Callable { holder.lock("admin_approval_request", "approval_id", id) }).get(60, TimeUnit.SECONDS)
                    val result = decided.get(60, TimeUnit.SECONDS)
                    holder.release()
                    result
                }
                pool.shutdown()
                withClue(mode) { outcome shouldBe ApprovalOutcome.RetryLater }
                // D-119(S8 리뷰 m-2): 지표는 TX가 끝난 뒤 — 실행 단계까지 갔다가 rollback된 시도는 EXECUTED +0, RETRY_LATER +1.
                withClue("$mode metrics") {
                    counter("paycore.admin.approval", "operation", "APPROVE", "kind", "PASSWORD_RESET", "result", "EXECUTED") shouldBe executedBefore
                    counter("paycore.admin.approval.retry_later", "operation", "APPROVE") shouldBe retryBefore + 1
                }
                (deadlocks() - before) shouldBe (if (mode == "1213") 1L else 0L)
                val old = storage.code(EnrollmentCodeHash.of(oldCode))!!
                listOf(storage.load(approver.account.id).lastTotpStep, statusOf(id), approvalAudits(id), old.usedAt, old.revokedAt) shouldBe
                    listOf(stepBefore, "PENDING", listOf("APPROVAL_REQUESTED"), null, null)
                approvals.approve(decision).shouldBeInstanceOf<ApprovalOutcome.Executed>()
                storage.code(EnrollmentCodeHash.of(oldCode))!!.revokedAt shouldNotBe null
            }
        }
    }

    Given("the request-creation TX as a forced 1213 victim (S5 review m-4 — INSERT, then two account rows in ascending order)") {
        Then("RETRY_LATER with no request row and no request audit; deadlock metric +1") {
            val requester = storage.securityAdmin()
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val before = deadlocks()
            val pool = Executors.newFixedThreadPool(2)
            val outcome = RowLockHolder.open(container).use { holder ->
                holder.makeHeavy()
                holder.lock("admin_account", "actor_id", target.id.value)
                val requested = pool.submit(Callable { approvals.request(RequestApproval(requester.id.value, gen(requester.id), "ROLE_GRANT", target.id.value, "VIEWER", null, path.context())) })
                // 생성 TX: 승인 행 INSERT → 요청자(낮은 ID) 잠금 → 대상 대기. 세 번째 연결이 요청자 행을 요구하면 교착.
                holder.awaitWaiters("admin_account", 1)
                pool.submit(Callable { holder.lock("admin_account", "actor_id", requester.id.value) }).get(60, TimeUnit.SECONDS)
                val result = requested.get(60, TimeUnit.SECONDS)
                holder.release()
                result
            }
            pool.shutdown()
            outcome shouldBe ApprovalOutcome.RetryLater
            (deadlocks() - before) shouldBe 1L
            AdminDbAccounts.root(container).use {
                (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_actor_id = ?", target.id.value) as Number).toInt() shouldBe 0
                it.column("SELECT action FROM admin_audit_event WHERE target_actor_id = ? AND action LIKE 'APPROVAL%'", target.id.value) shouldBe emptyList()
            }
        }
    }

    Given("lock wait timeout (1205 — innodb_lock_wait_timeout = 3 in this context)") {
        Then("approve, reject and withdraw waiting past the timeout are RETRY_LATER with no change") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val requester = storage.securityAdmin()
            val id = (approvals.request(RequestApproval(requester.id.value, gen(requester.id), "ROLE_GRANT", target.id.value, "VIEWER", null, path.context())) as ApprovalOutcome.Created)
                .approvalId
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val outcomes = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", requester.id.value)
                listOf(
                    approvals.approve(decide(id, approver)),
                    approvals.reject(decide(id, ready.bootstrapped(AdminRole.APPROVER))),
                    approvals.withdraw(WithdrawApproval(id, requester.id.value, gen(requester.id), path.context())),
                ).also { holder.release() }
            }
            outcomes shouldBe List(3) { ApprovalOutcome.RetryLater }
            listOf(statusOf(id), storage.load(approver.account.id).lastTotpStep, storage.load(target.id).activeRoles.size) shouldBe listOf("PENDING", 0L, 0)
        }
        Then("waiting past the timeout on the request row itself (approval repository 1205 classification) is RETRY_LATER for approve, reject and withdraw with no change") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val requester = storage.securityAdmin()
            val id = (approvals.request(RequestApproval(requester.id.value, gen(requester.id), "ROLE_GRANT", target.id.value, "VIEWER", null, path.context())) as ApprovalOutcome.Created)
                .approvalId
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val outcomes = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_approval_request", "approval_id", id)
                listOf(
                    approvals.approve(decide(id, approver)),
                    approvals.reject(decide(id, ready.bootstrapped(AdminRole.APPROVER))),
                    approvals.withdraw(WithdrawApproval(id, requester.id.value, gen(requester.id), path.context())),
                ).also { holder.release() }
            }
            outcomes shouldBe List(3) { ApprovalOutcome.RetryLater }
            listOf(statusOf(id), storage.load(approver.account.id).lastTotpStep, approvalAudits(id)) shouldBe listOf("PENDING", 0L, listOf("APPROVAL_REQUESTED"))
        }
    }

    Given("login, one-person command and enrollment TXs at the service boundary (S4 carry-over — retryLaterOnConflict)") {
        Then("password step (b) waiting past 1205 → HTTP 409 RETRY_LATER; no failure counted and no login audit row") {
            val account = ready.withoutRoles()
            val client = AdminHttpClient(mockMvc).also { it.csrf() }
            val response = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", account.account.id.value)
                client.password(account.loginName, account.password).also { holder.release() }
            }
            (response.status to response.contentAsString) shouldBe (409 to """{"error":"RETRY_LATER"}""")
            storage.load(account.account.id).failedAttempts shouldBe 0
            auditsOf(account.account.id).filter { it.startsWith("LOGIN_") } shouldBe emptyList()
        }
        Then("TOTP step waiting past 1205 → AdminRetryLaterException (409); generation and TOTP step unchanged, then the same step succeeds after release") {
            val account = ready.withoutRoles()
            val verified = login.verifyPassword(account.loginName, account.password.toCharArray(), path.context()).shouldBeInstanceOf<PasswordStepResult.Verified>().verified
            val before = storage.load(account.account.id)
            RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", account.account.id.value)
                shouldThrow<AdminRetryLaterException> { login.verifyTotp(verified, TestTotp.code(account.secret), path.context()) }
                holder.release()
            }
            storage.load(account.account.id).let { listOf(it.sessionGeneration, it.lastTotpStep, it.failedAttempts) } shouldBe
                listOf(before.sessionGeneration, before.lastTotpStep, 0)
            login.verifyTotp(verified, TestTotp.code(account.secret), path.context()).shouldBeInstanceOf<com.paycore.admin.account.application.provided.TotpStepResult.Authenticated>()
        }
        Then("suspend (1-person) waiting past 1205 on every attempt (D-69 retries) → AdminRetryLaterException; target ACTIVE, no suspension audit") {
            val admin = storage.securityAdmin()
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val ctx = path.context()
            RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", target.id.value)
                shouldThrow<AdminRetryLaterException> { commands.suspend(TargetedAccountCommand(admin.id.value, target.id.value, ctx)) }
                holder.release()
            }
            storage.load(target.id).status shouldBe AccountStatus.ACTIVE
            AdminDbAccounts.root(container).use { it.column("SELECT action FROM admin_audit_event WHERE correlation_id = ?", ctx.correlationId) } shouldBe emptyList()
        }
        Then("suspend as a forced 1213 victim is rolled back and re-judged in a new TX: exactly one suspension and one audit row") {
            val admin = storage.securityAdmin()
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val ctx = path.context()
            val before = deadlocks()
            val pool = Executors.newFixedThreadPool(2)
            val result = RowLockHolder.open(container).use { holder ->
                holder.makeHeavy()
                // 정지 TX: 행위자(낮은 ID) 잠금 → 대상 대기. 세 번째 연결이 행위자 행을 요구하면 교착.
                holder.lock("admin_account", "actor_id", target.id.value)
                val suspended = pool.submit(Callable { commands.suspend(TargetedAccountCommand(admin.id.value, target.id.value, ctx)) })
                holder.awaitWaiters("admin_account", 1)
                pool.submit(Callable { holder.lock("admin_account", "actor_id", admin.id.value) }).get(60, TimeUnit.SECONDS)
                // 희생된 정지 TX는 새 TX로 다시 시작해 다시 기다린다(재판정) → 놓으면 한 번만 적용.
                holder.awaitWaiters("admin_account", 1)
                holder.release()
                suspended.get(60, TimeUnit.SECONDS)
            }
            pool.shutdown()
            result shouldBe AccountCommandResult.Succeeded(target.id.value)
            ((deadlocks() - before) >= 1L) shouldBe true
            storage.load(target.id).status shouldBe AccountStatus.SUSPENDED
            AdminDbAccounts.root(container).use { it.column("SELECT CONCAT(action, ':', result_code) FROM admin_audit_event WHERE correlation_id = ?", ctx.correlationId) } shouldBe
                listOf("ACCOUNT_SUSPENDED:SUCCESS")
        }
        Then("enrollment completion waiting past 1205 → AdminRetryLaterException; the code stays unused and the same draft completes after release") {
            val target = path.withRolesViaApproval(AdminRole.VIEWER)
            val (_, code, _) = path.credentialReset("PASSWORD_RESET", target.id)
            val draft = (enrollment.begin(code) as EnrollmentResult.InProgress).draft
            val filled = (enrollment.providePassword(draft, "re-enrolled-password-${UUID.randomUUID().toString().take(4)}".toCharArray()) as EnrollmentResult.InProgress).draft
            RowLockHolder.open(container).use { holder ->
                holder.lock("admin_account", "actor_id", target.id.value)
                shouldThrow<AdminRetryLaterException> { enrollment.complete(filled, null, path.context()) }
                holder.release()
            }
            (storage.code(EnrollmentCodeHash.of(code))!!.usedAt to storage.load(target.id).status) shouldBe (null to AccountStatus.PENDING_ENROLLMENT)
            enrollment.complete(filled, null, path.context()) shouldBe EnrollmentResult.Completed(target.id.value)
        }
    }
})

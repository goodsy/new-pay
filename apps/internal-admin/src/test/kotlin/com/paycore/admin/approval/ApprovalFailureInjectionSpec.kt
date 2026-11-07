package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.adapter.integration.ApprovalSettings
import com.paycore.admin.approval.adapter.integration.MicrometerApprovalMetrics
import com.paycore.admin.approval.adapter.persistence.JpaApprovalRequestRepository
import com.paycore.admin.approval.application.CommitOutcomeUnknownException
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.required.ApprovalEnvironment
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.ImmutableApprovalColumns
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.InjectedAuditFailure
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManagerFactory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.DefaultTransactionStatus
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 승인 TX 실패·무결성·회차 잠금(§4.7.3): ① 승인 TX 중간(실행 감사 저장) 실패 주입 → 상태·계정·감사·TOTP 단계 모두 무변경, ② commit 뒤 응답 유실 → 다시 읽으면 EXECUTED·재승인은
 * ALREADY_DECIDED(수렴), ③ 0단계와 1단계 사이 불변 열 변경(root 연결로 강제 주입 — `ApprovalImmutableColumnIntegritySpec`) → 500 `INTERNAL_INTEGRITY`·무변경·별도 TX 감사 1행,
 * ④ SECURITY_ADMIN·APPROVER를 활성화할 수 있는 종류는 열린 복구 회차 행을 먼저 잠근다(회차 행에서 실제 대기), 그 밖 종류는 잠그지 않는다(§4.9.2).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class, SwitchableAuditStoreConfig::class, ApprovalFailureInjectionSpec.ProbeConfig::class)
class ApprovalFailureInjectionSpec(
    private val approvals: ApprovalUseCase,
    private val query: ApprovalQuery,
    private val path: ApprovalPath,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val auditStore: SwitchableAuditStore,
    private val probe: ProbingRepository,
    private val container: MySQLContainer,
    private val commitFaults: CommitFaultTransactionManager,
    private val clock: OffsetApprovalEnvironment,
    private val registry: MeterRegistry,
    private val acquiring: com.paycore.admin.approval.application.provided.AcquiringOpsApprovalUseCase,
    private val justifications: com.paycore.admin.approval.application.provided.OperatorCancelJustificationQuery,
) : BehaviorSpec({

    afterEach {
        auditStore.failOn(null)
        probe.afterImmutableRead.set(null)
        commitFaults.disarm()
        clock.offset.set(Duration.ZERO)
    }
    fun count(name: String, vararg tags: String): Double = registry.find(name).tags(*tags).counter()?.count() ?: 0.0
    fun decide(id: String, approver: LoginReadyAccounts.LoginReady) =
        DecideApproval(id, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, TestTotp.code(approver.secret), path.context())
    fun statusOf(id: String): String = AdminDbAccounts.root(container).use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun actions(id: String): List<String> = AdminDbAccounts.root(container).use { it.column("SELECT action FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) }

    Given("a failure inside the approval TX") {
        Then("the execution audit cannot be written → nothing commits: request PENDING, target unchanged, approver TOTP step not consumed") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            auditStore.failOn { it.action == "APPROVAL_EXECUTED" }
            shouldThrow<InjectedAuditFailure> { approvals.approve(decide(id, approver)) }
            listOf(statusOf(id), storage.load(target.id).activeRoles, storage.load(approver.account.id).lastTotpStep, actions(id)) shouldBe
                listOf("PENDING", emptySet<AdminRole>(), 0L, listOf("APPROVAL_REQUESTED"))
        }
        Then("a lost response after commit converges by re-reading: the request is EXECUTED and approving again is ALREADY_DECIDED (no second execution)") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            approvals.approve(decide(id, ready.bootstrapped(AdminRole.APPROVER))) shouldBe ApprovalOutcome.Executed(id)
            query.detail(storage.securityAdmin().id.value, id)?.status shouldBe "EXECUTED"
            approvals.approve(decide(id, ready.bootstrapped(AdminRole.APPROVER))) shouldBe ApprovalOutcome.AlreadyDecided
            actions(id).count { it == "APPROVAL_EXECUTED" } shouldBe 1
        }
    }

    Given("an exception thrown in the commit phase itself (S8 — real injection, not a sequential simulation)") {
        Then("the commit reached the database but the reply was lost → COMMIT_UNKNOWN (no retry, no success claimed); re-reading converges to EXECUTED and approving again is ALREADY_DECIDED") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val unknownBefore = count(MicrometerApprovalMetrics.DECISION, "operation", "APPROVE", "kind", "ROLE_GRANT", "result", "COMMIT_UNKNOWN")
            // 명령(세대 읽기 TX 포함)을 먼저 만든 뒤 무장한다 — 무장한 스레드의 **다음** commit 1회가 승인 TX여야 한다.
            val command = decide(id, ready.bootstrapped(AdminRole.APPROVER))
            commitFaults.armAfterCommit()
            shouldThrow<CommitOutcomeUnknownException> { approvals.approve(command) }
            count(MicrometerApprovalMetrics.DECISION, "operation", "APPROVE", "kind", "ROLE_GRANT", "result", "COMMIT_UNKNOWN") shouldBe unknownBefore + 1
            listOf(statusOf(id), storage.load(target.id).activeRoles) shouldBe listOf("EXECUTED", setOf(AdminRole.VIEWER))
            approvals.approve(decide(id, ready.bootstrapped(AdminRole.APPROVER))) shouldBe ApprovalOutcome.AlreadyDecided
            actions(id).count { it == "APPROVAL_EXECUTED" } shouldBe 1
        }
        Then("D-ADMIN1-021: a TransactionException in the commit phase is rolled back by the manager (rollbackOnCommitFailure) — a third connection locks the same rows at once and approving again executes once") {
            commitFaults.isRollbackOnCommitFailure shouldBe true
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val command = decide(id, approver)
            commitFaults.armTransactionExceptionBeforeCommit()
            shouldThrow<CommitOutcomeUnknownException> { approvals.approve(command) }
            RowLockHolder.open(container).use { holder ->
                // 1초 안에 못 잡으면 1205 — 잠금이 남아 있으면 여기서 실패한다.
                holder.update("SET SESSION innodb_lock_wait_timeout = 1")
                holder.lock("admin_approval_request", "approval_id", id)
                holder.lock("admin_account", "actor_id", approver.account.id.value)
                holder.lock("admin_account", "actor_id", target.id.value)
                holder.release()
            }
            listOf(statusOf(id), storage.load(approver.account.id).lastTotpStep) shouldBe listOf("PENDING", 0L)
            approvals.approve(command) shouldBe ApprovalOutcome.Executed(id)
            actions(id).count { it == "APPROVAL_EXECUTED" } shouldBe 1
        }
        Then("the commit failed before reaching the database → the same COMMIT_UNKNOWN to the caller, but nothing committed: PENDING, TOTP step unused; approving again executes once") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val command = decide(id, approver)
            commitFaults.armBeforeCommit()
            shouldThrow<CommitOutcomeUnknownException> { approvals.approve(command) }
            listOf(statusOf(id), storage.load(target.id).activeRoles, storage.load(approver.account.id).lastTotpStep, actions(id)) shouldBe
                listOf("PENDING", emptySet<AdminRole>(), 0L, listOf("APPROVAL_REQUESTED"))
            approvals.approve(decide(id, approver)) shouldBe ApprovalOutcome.Executed(id)
            actions(id).count { it == "APPROVAL_EXECUTED" } shouldBe 1
        }
    }

    Given("the expiry instant arrives while the approval waits on the request row lock (S8 — ApprovalEnvironment clock double, §22.11 m-2 carry-over)") {
        Then("the decision uses the TX time taken after the lock: EXPIRED is committed, nothing executes, the approver TOTP step is not consumed") {
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            val command = decide(id, approver)
            val pool = Executors.newSingleThreadExecutor()
            val outcome = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_approval_request", "approval_id", id)
                val waiting = pool.submit(Callable { approvals.approve(command) })
                holder.awaitWaiters("admin_approval_request", 1)
                // 기다리는 동안 유효 기간(24h)이 지난다.
                clock.offset.set(Duration.ofHours(25))
                holder.release()
                waiting.get(60, TimeUnit.SECONDS)
            }
            pool.shutdown()
            outcome shouldBe ApprovalOutcome.Expired
            listOf(statusOf(id), storage.load(target.id).activeRoles, storage.load(approver.account.id).lastTotpStep) shouldBe listOf("EXPIRED", emptySet<AdminRole>(), 0L)
            actions(id) shouldBe listOf("APPROVAL_REQUESTED", "APPROVAL_EXPIRED")
        }
    }

    Given("an immutable column changed between step 0 and step 1 (forced, root connection — rev 0.5 n-1)") {
        Then("500 INTERNAL_INTEGRITY: full rollback (TOTP step unchanged, no execution) and one integrity audit row in a separate TX naming the column only") {
            val violationsBefore = count(MicrometerApprovalMetrics.INTEGRITY_VIOLATION, "check", "kind")
            val target = storage.save(AdminAccountFixture.enrolledActive())
            val id = path.request("ROLE_GRANT", target.id, "VIEWER")
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            probe.afterImmutableRead.set {
                AdminDbAccounts.root(container).use { it.update("UPDATE admin_approval_request SET kind = 'PASSWORD_RESET', granted_role = NULL WHERE approval_id = ?", id) }
            }
            approvals.approve(decide(id, approver)) shouldBe ApprovalOutcome.IntegrityViolation
            listOf(statusOf(id), storage.load(approver.account.id).lastTotpStep, storage.load(target.id).activeRoles) shouldBe listOf("PENDING", 0L, emptySet<AdminRole>())
            AdminDbAccounts.root(container).use {
                it.column("SELECT CONCAT(action, ':', detail) FROM admin_audit_event WHERE actor_id = ? AND action = 'APPROVAL_INTEGRITY_VIOLATION'", approver.account.id.value)
            } shouldBe listOf("APPROVAL_INTEGRITY_VIOLATION:column=kind")
            // rev 0.5 n-1 "지표 증가"(S8): 경보 대상 counter가 그 검사 열 label로 1 증가.
            count(MicrometerApprovalMetrics.INTEGRITY_VIOLATION, "check", "kind") shouldBe violationsBefore + 1
        }
    }

    Given("HT-3 ④ (ACQ-ADMIN-001 B2 — B1 review n-A, D-ACQA-005): the re-acquisition evidence lookup when its audit row cannot be written") {
        // 위치: 감사 저장 실패 주입(SwitchableAuditStore)이 있는 이 컨텍스트에 둔다 — AcquiringOpsApprovalHttpSpec(HT-1..3)에 주입 구성을 더하면 Spring 컨텍스트가 하나 늘어난다
        // (TEST-PERF-002). 서비스 결과 AuditUnavailable → HTTP 503 `AUDIT_UNAVAILABLE`은 ApprovalController의 같은 분기(D-OPS-010 선례)다.
        Then("fail-closed: AuditUnavailable (503) without the reference and no audit row; with a working store the same viewer reads it and one OK row is written") {
            com.paycore.admin.support.StubFinancialOpsServer.start()
            val fileId = "ACQ.N${System.nanoTime() % 100_000_000L}.TEST.20261105.001"
            com.paycore.admin.support.StubFinancialOpsServer.reply(
                com.paycore.admin.support.StubFinancialOpsServer.targetsPath(fileId),
                com.paycore.admin.support.StubFinancialOpsServer.Reply(
                    200, com.paycore.admin.support.StubFinancialOpsServer.targetsJson(fileId, com.paycore.admin.support.StubFinancialOpsServer.targetItemJson(1)),
                ),
            )
            val operator = path.withRolesViaApproval(AdminRole.OPERATOR)
            val created = acquiring.requestReacquisition(
                com.paycore.admin.approval.application.provided.RequestReacquisition(
                    operator.id.value, storage.load(operator.id).sessionGeneration, fileId, listOf(1), "INSTITUTION_REQUESTED", "INQ-AUDF-0007", "audit failure reason", path.context(),
                ),
            ).shouldBeInstanceOf<ApprovalOutcome.Created>()
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            fun viewed() = AdminDbAccounts.root(container).use {
                it.column("SELECT result_code FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_JUSTIFICATION_VIEWED'", created.approvalId)
            }
            auditStore.failOn { it.action == "APPROVAL_JUSTIFICATION_VIEWED" }
            val failed = justifications.view(approver.account.id.value, created.approvalId, path.context())
            failed shouldBe com.paycore.admin.approval.application.provided.JustificationResult.AuditUnavailable
            failed.toString().contains("INQ-AUDF-0007") shouldBe false
            viewed() shouldBe emptyList()
            auditStore.failOn(null)
            justifications.view(approver.account.id.value, created.approvalId, path.context())
                .shouldBeInstanceOf<com.paycore.admin.approval.application.provided.JustificationResult.EvidenceFound>().evidenceRef shouldBe "INQ-AUDF-0007"
            viewed() shouldBe listOf("OK")
        }
    }

    Given("an open recovery episode (§4.9.2 — activation serialization)") {
        Then("a ROLE_GRANT of APPROVER waits on the episode row; a PASSWORD_RESET does not lock it") {
            AdminDbAccounts.root(container).use {
                it.update("INSERT INTO admin_recovery_episode (episode_no, opened_at, expires_at) VALUES (9001, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) + INTERVAL 1 DAY)")
            }
            try {
                val privileged = path.request("ROLE_GRANT", storage.save(AdminAccountFixture.enrolledActive()).id, "APPROVER")
                val reset = path.request("PASSWORD_RESET", storage.save(AdminAccountFixture.enrolledActive()).id)
                val (a1, a2) = ready.bootstrapped(AdminRole.APPROVER) to ready.bootstrapped(AdminRole.APPROVER)
                val (d1, d2) = decide(privileged, a1) to decide(reset, a2)
                val pool = Executors.newSingleThreadExecutor()
                val results = RowLockHolder.open(container).use { holder ->
                    holder.lock("admin_recovery_episode", "episode_no", 9001)
                    // PASSWORD_RESET은 회차를 잠그지 않으므로 회차 행이 잡혀 있어도 끝난다.
                    approvals.approve(d2).shouldBeInstanceOf<ApprovalOutcome.Executed>().approvalId shouldBe reset
                    val waiting = pool.submit(Callable { approvals.approve(d1) })
                    holder.awaitWaiters("admin_recovery_episode", 1)
                    holder.release()
                    waiting.get(60, TimeUnit.SECONDS)
                }
                pool.shutdown()
                results shouldBe ApprovalOutcome.Executed(privileged)
                statusOf(reset) shouldBe "EXECUTED"
            } finally {
                AdminDbAccounts.root(container).use { it.update("UPDATE admin_recovery_episode SET closed_at = UTC_TIMESTAMP(3) WHERE episode_no = 9001") }
            }
        }
    }
}) {
    /** 0단계 비잠금 읽기 직후 고리를 실행하는 저장 Port(시험 전용 — 실제 JPA 구현에 위임). */
    class ProbingRepository(private val delegate: JpaApprovalRequestRepository) : ApprovalRequestRepository by delegate {
        val afterImmutableRead = AtomicReference<(() -> Unit)?>(null)

        override fun immutableColumns(id: String): ImmutableApprovalColumns? =
            delegate.immutableColumns(id).also { afterImmutableRead.getAndSet(null)?.invoke() }
    }

    /**
     * commit 단계 예외 주입(시험 전용 — 실제 JPA 트랜잭션 관리자를 상속): 무장한 **그 스레드**의 다음 commit 1회만. [armAfterCommit]은 DB commit 뒤 응답 유실,
     * [armBeforeCommit]은 DB에 닿기 전 실패(관리자가 rollback). Boot의 기본 `transactionManager`는 이 bean이 있으면 만들어지지 않는다.
     */
    class CommitFaultTransactionManager(emf: EntityManagerFactory) : JpaTransactionManager(emf) {
        private val after = AtomicReference<Thread?>(null)
        private val before = AtomicReference<Thread?>(null)
        private val beforeTx = AtomicReference<Thread?>(null)

        fun armAfterCommit() = after.set(Thread.currentThread())

        fun armBeforeCommit() = before.set(Thread.currentThread())

        fun armTransactionExceptionBeforeCommit() = beforeTx.set(Thread.currentThread())

        fun disarm() {
            after.set(null)
            before.set(null)
            beforeTx.set(null)
        }

        override fun doCommit(status: DefaultTransactionStatus) {
            // DB에 닿기 전 실패: TransactionException이 아닌 런타임 예외라 관리자가 실제 rollback한다(processCommit — TransactionException은
            // rollbackOnCommitFailure=false 기본값이면 rollback 없이 연결을 돌려준다 — 실측 S8, §25 기록).
            if (before.compareAndSet(Thread.currentThread(), null)) throw IllegalStateException("injected: commit failed before reaching the database")
            // DB에 닿기 전 TransactionException(JDBC commit 실패와 같은 분류) — rollbackOnCommitFailure가 참이면 관리자가 rollback한다(D-ADMIN1-021).
            if (beforeTx.compareAndSet(Thread.currentThread(), null)) throw TransactionSystemException("injected: commit failed (transaction exception)")
            super.doCommit(status)
            if (after.compareAndSet(Thread.currentThread(), null)) throw TransactionSystemException("injected: commit reply lost")
        }
    }

    /** 승인 시계 대역: 실제 설정(ID·유효 기간)에 위임하고 시각만 [offset]만큼 민다. */
    class OffsetApprovalEnvironment(private val delegate: ApprovalSettings) : ApprovalEnvironment by delegate {
        val offset = AtomicReference(Duration.ZERO)

        override fun now(): Instant = delegate.now().plus(offset.get())
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ProbeConfig {
        @Bean
        @Primary
        fun probingRepository(delegate: JpaApprovalRequestRepository): ProbingRepository = ProbingRepository(delegate)

        /** Boot 기본 관리자와 같은 설정을 받는다(`spring.transaction.*` — D-ADMIN1-021 `rollback-on-commit-failure`). */
        @Bean
        fun transactionManager(emf: EntityManagerFactory, customizers: ObjectProvider<TransactionManagerCustomizers>): CommitFaultTransactionManager =
            CommitFaultTransactionManager(emf).also { manager -> customizers.ifAvailable { it.customize(manager) } }

        @Bean
        @Primary
        fun offsetApprovalEnvironment(delegate: ApprovalSettings): OffsetApprovalEnvironment = OffsetApprovalEnvironment(delegate)
    }
}

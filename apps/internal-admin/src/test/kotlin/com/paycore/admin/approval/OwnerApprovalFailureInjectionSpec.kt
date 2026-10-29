package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer

/**
 * 소유 서비스 승인 실패 주입(ADMIN-PAYMENT-OPS-001 §7.2):
 * - 서비스 수준 1213 → 409 `RETRY_LATER`(L-9 — `UnresolvedApprovalRaceSpec`의 서비스 행): 승인·명령·감사 행 0, 같은 Payment 새 요청은 이후 성공(미해결 키를 남기지 않음).
 * - `OwnerExecutionTx2FailureSpec`: PAYMENT가 STARTED를 돌려준 뒤 TX2 저장이 실패 → 승인 EXECUTING 유지(회복 job이 이어받음 — §4.13 D), 응답 200 `EXECUTING`,
 *   POST 1회. 같은 승인을 다시 승인해도 `ALREADY_DECIDED`이고 POST가 더 나가지 않는다(이중 실행 없음).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class, OwnerFailureInjectionConfig::class)
class OwnerApprovalFailureInjectionSpec(
    owners: OwnerServiceApprovalUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val approvals: InjectingApprovalRepository,
    private val executions: InjectingExecutionRepository,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    fun root() = AdminDbAccounts.root(container)
    fun count(sql: String, vararg args: Any?) = root().use { (it.scalar(sql, *args) as Number).toLong() }

    Given("a deadlock (1213) on the approval INSERT of the request transaction") {
        Then("409 RETRY_LATER: nothing stored (approval, command, audit), and a retry of the same Payment succeeds") {
            val operator = fixture.operator()
            val paymentId = fixture.newPaymentId()
            StubPaymentOpsServer.targetFound(paymentId)
            approvals.deadlockOnInsert.set(true)
            val outcome = try {
                fixture.request(operator, paymentId)
            } finally {
                approvals.deadlockOnInsert.set(false)
            }
            outcome shouldBe ApprovalOutcome.RetryLater
            listOf(
                count("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", paymentId),
                count("SELECT COUNT(*) FROM admin_operator_cancel_command WHERE payment_id = ?", paymentId),
                count("SELECT COUNT(*) FROM admin_audit_event WHERE target_ref = ?", paymentId),
            ) shouldBe listOf(0L, 0L, 0L)
            (fixture.request(operator, paymentId) is ApprovalOutcome.Created) shouldBe true
        }
    }

    Given("(d) I-89: the approval TX1 commit outcome is unknown (connection lost at commit)") {
        Then("committed (execution row present): the stored body is POSTed exactly once and the result is applied (EXECUTED)") {
            val (id, _) = fixture.requested()
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000CW01\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
            CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.COMMITTED_THEN_LOST)
            val outcome = try {
                fixture.approve(id)
            } finally {
                CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.OFF)
            }
            outcome shouldBe ApprovalOutcome.Executed(id)
            StubPaymentOpsServer.requestsFor(id).count { it.method == "POST" } shouldBe 1
            root().use { it.column("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) } shouldBe listOf("EXECUTED")
        }
        Then("not committed (no execution row): the original commit failure propagates, PENDING, no POST") {
            val (id, _) = fixture.requested()
            CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.NOT_COMMITTED)
            val thrown = try {
                fixture.approve(id)
                null
            } catch (e: RuntimeException) {
                e
            } finally {
                CommitUnknownTransactionManager.mode.set(CommitUnknownTransactionManager.Mode.OFF)
            }
            (thrown?.javaClass?.simpleName) shouldBe "CommitOutcomeUnknownException"
            StubPaymentOpsServer.requestsFor(id).count { it.method == "POST" } shouldBe 0
            root().use { it.column("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) } shouldBe listOf("PENDING")
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_owner_execution WHERE approval_id = ?", id) as Number).toLong() } shouldBe 0L
        }
    }

    Given("PAYMENT answered STARTED but the TX2 storage fails (OwnerExecutionTx2FailureSpec)") {
        Then("the approval stays EXECUTING (200 EXECUTING), exactly one POST, and approving again sends nothing (ALREADY_DECIDED)") {
            val (id, _) = fixture.requested()
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000TX21\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
            executions.failExecutionUpdate.set(true)
            val outcome = try {
                fixture.approve(id)
            } finally {
                executions.failExecutionUpdate.set(false)
            }
            outcome shouldBe ApprovalOutcome.Executing(id, "EXECUTING")
            root().use {
                it.column(
                    "SELECT CONCAT(r.status, '|', COALESCE(e.owner_reference, '-'), '|', e.attempt_count) FROM admin_approval_request r " +
                        "JOIN admin_owner_execution e ON e.approval_id = r.approval_id WHERE r.approval_id = ?",
                    id,
                )
            } shouldBe listOf("EXECUTING|-|1")
            fixture.approve(id) shouldBe ApprovalOutcome.AlreadyDecided
            StubPaymentOpsServer.requestsFor(id).count { it.method == "POST" } shouldBe 1
        }
    }
})

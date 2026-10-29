package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 승인 TX1의 보안 분기(ADMIN-PAYMENT-OPS-001 §4.13 B — `OwnerApprovalStepUpSpec`, S5 리뷰 M-1):
 * - (a) 틀린 TOTP → `STEP_UP_FAILED`, 승인 PENDING, 실패 계수 +1(별도 TX), POST 0.
 * - (b) 요청자가 승인 전에 OPERATOR를 잃음 → `INVALIDATED(REQUESTER_NOT_ELIGIBLE)`, 승인자 TOTP 단계 소비는 함께 커밋, POST 0.
 * - (c) A0(세션 역할 확인) 뒤 TX1 전에 승인자의 APPROVER가 회수됨 → TX1에서 `DENIED`, 상태·TOTP 불변, POST 0(대역이 A 단계 조회를 늦춰 그 사이에 회수).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OwnerApprovalStepUpSpec(
    private val owners: OwnerServiceApprovalUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val commands: AdminAccountCommandUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    fun root() = AdminDbAccounts.root(container)
    fun status(id: String) = root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun account(actorId: String) = root().use { it.column("SELECT CONCAT(COALESCE(last_totp_step, -1), '|', failed_attempts) FROM admin_account WHERE actor_id = ?", actorId).single() }
    fun posts(id: String) = StubPaymentOpsServer.requestsFor(id).count { it.method == "POST" }
    fun revoke(actorId: String, role: String) =
        commands.revokeRole(RevokeAdminRole(storage.securityAdmin().id.value, actorId, role, path.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()

    Given("(a) a wrong step-up TOTP") {
        Then("STEP_UP_FAILED: PENDING, the failure is counted, the step is not consumed, no POST") {
            val (id, _) = fixture.requested()
            val approver = fixture.approver()
            val before = account(approver.account.id.value)
            val wrong = TestTotp.code(approver.secret).let { if (it == "000000") "111111" else "000000" }
            val outcome = owners.approve(DecideApproval(id, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, wrong, path.context()))
            outcome shouldBe ApprovalOutcome.StepUpFailed
            listOf(status(id), posts(id).toString()) shouldBe listOf("PENDING", "0")
            val (stepBefore, failuresBefore) = before.split('|')
            val (stepAfter, failuresAfter) = account(approver.account.id.value).split('|')
            listOf(stepAfter, failuresAfter.toInt() - failuresBefore.toInt()) shouldBe listOf<Any>(stepBefore, 1)
        }
    }

    Given("(b) the requester lost OPERATOR before the approval") {
        Then("INVALIDATED(REQUESTER_NOT_ELIGIBLE) with the approver's TOTP step consumed, no POST") {
            val operator = fixture.operator()
            val (id, _) = fixture.requested(operator)
            revoke(operator.id.value, "OPERATOR")
            val approver = fixture.approver()
            val before = account(approver.account.id.value)
            fixture.approve(id, approver) shouldBe ApprovalOutcome.Invalidated("REQUESTER_NOT_ELIGIBLE")
            listOf(status(id), posts(id).toString()) shouldBe listOf("INVALIDATED", "0")
            account(approver.account.id.value).split('|')[0] shouldNotBe before.split('|')[0]
        }
    }

    Given("(c) the approver's APPROVER role is revoked after A0 and before TX1") {
        Then("TX1 refuses (DENIED): PENDING, TOTP step not consumed, no POST") {
            val (id, paymentId) = fixture.requested()
            StubPaymentOpsServer.target(
                paymentId,
                StubPaymentOpsServer.Reply(
                    200,
                    "{\"paymentId\":\"$paymentId\",\"paymentStatus\":\"AUTHORIZED\",\"cancellable\":true,\"currency\":\"KRW\",\"authorizedMinor\":10000,\"cancellableMinor\":10000," +
                        "\"balanceRevision\":3,\"onHold\":false,\"inFlightCancel\":false}",
                    delayMillis = 1_200,
                ),
            )
            val approver = fixture.approver()
            val decide = DecideApproval(id, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, TestTotp.code(approver.secret), path.context())
            val before = account(approver.account.id.value)
            val lookupsBefore = StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/$paymentId/operator-cancel-target").size
            val running = CompletableFuture.supplyAsync { owners.approve(decide) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (StubPaymentOpsServer.requestsForPath("/internal/v1/admin/payments/$paymentId/operator-cancel-target").size == lookupsBefore && System.nanoTime() < deadline) Thread.sleep(10)
            revoke(approver.account.id.value, "APPROVER")
            running.get(30, TimeUnit.SECONDS) shouldBe ApprovalOutcome.Denied
            listOf(status(id), posts(id).toString(), account(approver.account.id.value)) shouldBe listOf("PENDING", "0", before)
        }
    }
})

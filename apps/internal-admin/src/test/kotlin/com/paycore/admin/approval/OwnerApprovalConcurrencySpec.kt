package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 두 APPROVER 동시 승인(ADMIN-PAYMENT-OPS-001 §7.2 `OwnerApprovalConcurrencySpec`, §5 "비관적 잠금 + 순서"): 세 번째 연결([RowLockHolder])이 승인 행을 잡은 채
 * 두 승인 TX1이 **실제로 그 행에서 기다리는 것을 확인한 뒤** 놓는다(우연한 스케줄에 기대지 않음). 기대: 한쪽만 실행(EXECUTED — 실행 행 1, POST 1),
 * 다른 쪽은 `ALREADY_DECIDED`이고 그 승인자의 TOTP 단계는 소비되지 않는다(rollback 없이 판정표에서 끝남).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OwnerApprovalConcurrencySpec(
    owners: OwnerServiceApprovalUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    fun root() = AdminDbAccounts.root(container)
    fun lastStep(actorId: String) = root().use { it.scalar("SELECT COALESCE(last_totp_step, -1) FROM admin_account WHERE actor_id = ?", actorId).toString() }

    Given("two APPROVERs approving the same OPERATOR_CORRECTION_CANCEL at the same time") {
        Then("one executes (one execution row, one POST); the other is ALREADY_DECIDED without consuming its TOTP step") {
            val (id, _) = fixture.requested()
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000CC01\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
            val a = fixture.approver()
            val b = fixture.approver()
            val stepsBefore = listOf(lastStep(a.account.id.value), lastStep(b.account.id.value))
            val pool = Executors.newFixedThreadPool(2)
            val outcomes = RowLockHolder.open(container).use { holder ->
                holder.lock("admin_approval_request", "approval_id", id)
                val futures = listOf(Callable { fixture.approve(id, a) }, Callable { fixture.approve(id, b) }).map(pool::submit)
                try {
                    holder.awaitWaiters("admin_approval_request", 2)
                } finally {
                    holder.release()
                }
                futures.map { it.get(60, TimeUnit.SECONDS) }
            }
            pool.shutdownNow()
            outcomes.toSet() shouldBe setOf(ApprovalOutcome.Executed(id), ApprovalOutcome.AlreadyDecided)
            val loser = if (outcomes[0] == ApprovalOutcome.AlreadyDecided) 0 else 1
            val loserAccount = listOf(a, b)[loser]
            lastStep(loserAccount.account.id.value) shouldBe stepsBefore[loser]
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_owner_execution WHERE approval_id = ?", id) as Number).toLong() } shouldBe 1L
            StubPaymentOpsServer.requestsFor(id).count { it.method == "POST" } shouldBe 1
        }
    }
})

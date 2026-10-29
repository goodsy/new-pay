package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.RequestApproval
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.domain.OwnerServiceApprovalFixture
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 소유 서비스 승인(`OPERATOR_CORRECTION_CANCEL`, Payment 대상) 행이 **기존 단일 TX 경로**를 지날 때(ADMIN-PAYMENT-OPS-001 §4.10, M-6 — S4 리뷰 이월 필수 항목):
 * - M-1: 기존 요청 경로는 소유 서비스 종류를 종류 해석 단계에서 거부(`WRONG_EXECUTION_MODEL`) — 승인 행 0, 감사 0.
 * - 3: 기존 승인 경로(`ApprovalService.approve`)에 소유 서비스 행이 오면 rollback + `IntegrityViolation`(500) + 별도 TX 감사 1행, 상태·TOTP 불변(fail-closed).
 * - 2: 거절·철회 감사는 `target_ref = paymentId`, `target_actor_id` NULL(§4.10 감사 행).
 * - 6: 철회 요청자 재검사는 종류의 요청자 역할(OPERATOR) — 역할을 잃은 요청자는 철회할 수 없다.
 * - 4: 목록·상세에 Payment 행이 섞여도 조회가 실패하지 않는다.
 * 행은 domain 공식 경로(`OwnerServiceApprovalFixture.pending` → 저장 Port `insert`)로 만든다(요청 Use Case는 S5 본체 — 여기서는 그 전 단계의 방어를 고정).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OwnerServiceOnLegacyPathSpec(
    private val approvals: ApprovalUseCase,
    private val query: ApprovalQuery,
    private val repository: ApprovalRequestRepository,
    private val commands: AdminAccountCommandUseCase,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val path: ApprovalPath,
    transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    val seq = AtomicLong(System.nanoTime() % 1_000_000_000L * 1000 + 7)
    fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    fun root() = AdminDbAccounts.root(container)
    fun gen(actorId: String) = storage.load(com.paycore.admin.account.domain.AdminActorId(actorId)).sessionGeneration

    /** OPERATOR(2인 승인 경로로 역할을 받은 계정)가 요청한 Payment 대상 PENDING 승인을 저장한다. */
    fun storedPaymentApproval(requester: String, requestedAt: Instant = now()): Pair<String, String> {
        val id = OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet())
        val paymentId = OwnerServiceApprovalFixture.paymentId(seq.incrementAndGet())
        tx.execute { repository.insert(OwnerServiceApprovalFixture.pending(id, requester, requestedAt, paymentId)) }
        return id to paymentId
    }
    fun statusOf(id: String) = root().use { it.scalar("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun auditRows(id: String) = root().use {
        it.column(
            "SELECT CONCAT(action, '|', COALESCE(target_actor_id, '-'), '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event " +
                "WHERE approval_id = ? ORDER BY event_id",
            id,
        )
    }

    Given("M-1: the single-TX request path receives the owner-service kind") {
        Then("it is rejected at kind resolution (WRONG_EXECUTION_MODEL): no approval row, no audit row") {
            val requester = storage.securityAdmin()
            val target = storage.save(com.paycore.admin.account.AdminAccountFixture.enrolledActive())
            val countBefore = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request") as Number).toLong() to (it.scalar("SELECT COUNT(*) FROM admin_audit_event") as Number).toLong() }
            val outcome = approvals.request(
                RequestApproval(requester.id.value, requester.sessionGeneration, "OPERATOR_CORRECTION_CANCEL", target.id.value, null, null, path.context()),
            )
            outcome shouldBe ApprovalOutcome.Rejected("WRONG_EXECUTION_MODEL")
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request") as Number).toLong() to (it.scalar("SELECT COUNT(*) FROM admin_audit_event") as Number).toLong() } shouldBe
                countBefore
        }
    }

    Given("3: the single-TX approve path receives a PENDING owner-service approval") {
        Then("rollback + IntegrityViolation (500), status PENDING, the approver's TOTP step not consumed, one separate-TX integrity audit row") {
            val operator = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (id, paymentId) = storedPaymentApproval(operator.id.value)
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            fun lastStep() = root().use { it.scalar("SELECT COALESCE(last_totp_step, -1) FROM admin_account WHERE actor_id = ?", approver.account.id.value).toString() }
            val stepBefore = lastStep()
            val outcome = approvals.approve(DecideApproval(id, approver.account.id.value, gen(approver.account.id.value), TestTotp.code(approver.secret), path.context()))
            listOf(outcome, statusOf(id), lastStep()) shouldBe listOf(ApprovalOutcome.IntegrityViolation, "PENDING", stepBefore)
            root().use {
                it.column(
                    "SELECT detail FROM admin_audit_event WHERE action = 'APPROVAL_INTEGRITY_VIOLATION' AND actor_id = ? AND approval_id = ? AND target_ref = ? AND target_actor_id IS NULL ORDER BY event_id", approver.account.id.value, id, paymentId,
                )
            } shouldBe listOf("column=execution_model")
        }
    }

    Given("2: reject and withdraw of a Payment-target approval through the existing path") {
        Then("reject closes it and its audit row names the Payment in target_ref (target_actor_id NULL)") {
            val operator = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (id, paymentId) = storedPaymentApproval(operator.id.value)
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            approvals.reject(DecideApproval(id, approver.account.id.value, gen(approver.account.id.value), TestTotp.code(approver.secret), path.context())) shouldBe
                ApprovalOutcome.Closed("REJECTED")
            auditRows(id) shouldBe listOf("APPROVAL_REJECTED|-|$paymentId|kind=OPERATOR_CORRECTION_CANCEL")
        }
        Then("withdraw by the requesting OPERATOR closes it with a target_ref audit row; a refused withdraw (not the requester) audits target_ref too") {
            val operator = path.withRolesViaApproval(AdminRole.OPERATOR)
            val other = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (id, paymentId) = storedPaymentApproval(operator.id.value)
            approvals.withdraw(WithdrawApproval(id, other.id.value, gen(other.id.value), path.context())) shouldBe ApprovalOutcome.Refused("NOT_REQUESTER")
            approvals.withdraw(WithdrawApproval(id, operator.id.value, gen(operator.id.value), path.context())) shouldBe ApprovalOutcome.Closed("WITHDRAWN")
            auditRows(id) shouldBe listOf(
                "APPROVAL_DENIED|-|$paymentId|kind=OPERATOR_CORRECTION_CANCEL;reason=NOT_REQUESTER", "APPROVAL_WITHDRAWN|-|$paymentId|kind=OPERATOR_CORRECTION_CANCEL",
            )
        }
        Then("6: a requester who lost the OPERATOR role cannot withdraw (Denied, status unchanged, denial audited with target_ref)") {
            val operator = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (id, paymentId) = storedPaymentApproval(operator.id.value)
            val admin = storage.securityAdmin()
            commands.revokeRole(RevokeAdminRole(admin.id.value, operator.id.value, "OPERATOR", path.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()
            approvals.withdraw(WithdrawApproval(id, operator.id.value, gen(operator.id.value), path.context())) shouldBe ApprovalOutcome.Denied
            statusOf(id) shouldBe "PENDING"
            auditRows(id) shouldBe listOf("APPROVAL_DENIED|-|$paymentId|reason=REQUESTER_NOT_ELIGIBLE")
        }
        Then("an expired Payment-target approval expires on reject with a target_ref audit row") {
            val operator = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (id, paymentId) = storedPaymentApproval(operator.id.value, now().minus(Duration.ofHours(25)))
            val approver = ready.bootstrapped(AdminRole.APPROVER)
            approvals.reject(DecideApproval(id, approver.account.id.value, gen(approver.account.id.value), TestTotp.code(approver.secret), path.context())) shouldBe
                ApprovalOutcome.Expired
            auditRows(id) shouldBe listOf("APPROVAL_EXPIRED|-|$paymentId|kind=OPERATOR_CORRECTION_CANCEL")
        }
    }

    Given("4: the approval list and detail with a Payment-target row among account rows") {
        Then("they do not fail; the Payment row is listed with its kind") {
            val operator = path.withRolesViaApproval(AdminRole.OPERATOR)
            val (id, _) = storedPaymentApproval(operator.id.value)
            val viewer = ready.bootstrapped(AdminRole.APPROVER).account.id.value
            val listed = query.list(viewer, pendingOnly = true)!!.firstOrNull { it.approvalId == id }
            listOf(listed?.kind, query.detail(viewer, id)?.kind) shouldBe listOf("OPERATOR_CORRECTION_CANCEL", "OPERATOR_CORRECTION_CANCEL")
        }
    }
})

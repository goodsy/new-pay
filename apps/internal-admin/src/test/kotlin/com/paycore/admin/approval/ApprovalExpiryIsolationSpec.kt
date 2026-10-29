package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.OwnerServiceApprovalFixture
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.SwitchableAuditStore
import com.paycore.admin.support.SwitchableAuditStoreConfig
import com.paycore.admin.support.column
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
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
 * 만료 정리 job의 행 단위 격리(S4 리뷰 이월 필수 1 — High): 한 행의 처리가 실패해도(감사 저장 실패 주입) 그 행만 PENDING으로 남고 나머지 행은 만료된다 —
 * job 전체가 영구 정지하지 않는다. 실패 행은 지표 `paycore.admin.approval{operation=EXPIRE_JOB,result=ERROR}`로 보이고 다음 실행에서 다시 시도된다.
 * Payment 대상 행의 만료 감사는 `target_ref = paymentId`(target_actor_id NULL).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, SwitchableAuditStoreConfig::class)
class ApprovalExpiryIsolationSpec(
    private val approvals: ApprovalUseCase,
    private val repository: ApprovalRequestRepository,
    private val auditStore: SwitchableAuditStore,
    private val registry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    val seq = AtomicLong(System.nanoTime() % 1_000_000_000L * 1000 + 11)
    val longAgo = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(25))
    val requester = "adm_" + "E".repeat(26)
    fun root() = AdminDbAccounts.root(container)
    fun statusOf(id: String) = root().use { it.column("SELECT status FROM admin_approval_request WHERE approval_id = ?", id).single() }
    fun errors(): Double = registry.find("paycore.admin.approval").tags("operation", "EXPIRE_JOB", "result", "ERROR").counter()?.count() ?: 0.0

    Given("three expired PENDING approvals (two Payment targets, one account target) and an audit failure injected for one Payment row") {
        val failing = OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet())
        val payment = OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet())
        val account = OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet())
        val paymentRef = OwnerServiceApprovalFixture.paymentId(seq.incrementAndGet())
        tx.execute {
            repository.insert(OwnerServiceApprovalFixture.pending(failing, requester, longAgo, OwnerServiceApprovalFixture.paymentId(seq.incrementAndGet())))
            repository.insert(OwnerServiceApprovalFixture.pending(payment, requester, longAgo, paymentRef))
            repository.insert(ApprovalRequest.create(account, ApprovalKind.ACCOUNT_UNLOCK, "adm_" + "T".repeat(26), 1, "{}", null, null, requester, "R", longAgo, Duration.ofHours(24)))
        }

        Then("the failing row stays PENDING, the others expire, the failure is counted, and the next run expires the failed row") {
            val errorsBefore = errors()
            auditStore.failOn { it.action == "APPROVAL_EXPIRED" && it.approvalId == failing }
            val expired = try {
                approvals.expireDue()
            } finally {
                auditStore.failOn(null)
            }
            expired shouldBeGreaterThanOrEqual 2
            listOf(statusOf(failing), statusOf(payment), statusOf(account)) shouldBe listOf("PENDING", "EXPIRED", "EXPIRED")
            (errors() - errorsBefore) shouldBe 1.0
            approvals.expireDue() shouldBeGreaterThanOrEqual 1
            statusOf(failing) shouldBe "EXPIRED"
        }

        Then("the Payment row's expiry audit names the Payment in target_ref (actor SYSTEM_JOB, target_actor_id NULL)") {
            root().use {
                it.column(
                    "SELECT CONCAT(actor_kind, '|', COALESCE(target_actor_id, '-'), '|', COALESCE(target_ref, '-')) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_EXPIRED'",
                    payment,
                )
            } shouldBe listOf("SYSTEM_JOB|-|$paymentRef")
        }
    }
})

package com.paycore.admin.approval

import com.paycore.admin.approval.application.required.ApprovalPersistenceFailure
import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.PinnedReacquisitionItem
import com.paycore.admin.approval.domain.PinnedReacquisitionTarget
import java.time.Duration
import com.paycore.admin.approval.domain.OwnerServiceApprovalFixture
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Payment 하나에 미해결 승인 하나(ADMIN-PAYMENT-OPS-001 §4.11 `unresolved_key` UNIQUE, I-14 — S4는 저장 Port 수준: 서비스의 409 `DUPLICATE_UNRESOLVED`·
 * 1213 → `RETRY_LATER` 매핑은 S5 요청 서비스 시험). 실제 MySQL 두 연결:
 * - 같은 Payment 동시 INSERT: 첫 TX가 커밋하지 않은 채 두 번째 INSERT가 **UNIQUE 검사에서 기다리는 것을 확인한 뒤** 첫 TX를 커밋 → 두 번째는 1062 →
 *   `ApprovalPersistenceFailure.DuplicateUnresolved`(정확히 한 건), 행은 1개.
 * - PENDING·EXECUTING·EXECUTION_UNKNOWN은 모두 같은 Payment의 새 요청을 막고, 종료 상태로 가면 풀린다(UNKNOWN을 키에서 빼는 변이를 잡는다).
 * - 다른 Payment는 서로 막지 않는다. 기존 계정 대상 승인은 미해결 키가 없다.
 * 대기 확인은 시간 상한(20초)이 있고, 상한에 걸려도 첫 TX를 끝내 두 번째 TX가 무한 대기하지 않는다.
 */
@SpringBootTest
@AdminDbTest
class UnresolvedApprovalRaceSpec(
    private val approvals: ApprovalRequestRepository,
    transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    val seq = AtomicLong(System.nanoTime() % 1_000_000_000L * 1000 + 500)
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val requester = "adm_" + "Q".repeat(26)
    val approver = "adm_" + "P".repeat(26)
    fun newPending(paymentId: String): ApprovalRequest = OwnerServiceApprovalFixture.pending(OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet()), requester, now, paymentId)
    fun newPayment() = OwnerServiceApprovalFixture.paymentId(seq.incrementAndGet())

    /** 새 TX에서 INSERT — 성공이면 null, 미해결 중복이면 "DUPLICATE_UNRESOLVED". */
    fun insert(request: ApprovalRequest): String? = try {
        tx.execute { approvals.insert(request) }
        null
    } catch (_: ApprovalPersistenceFailure.DuplicateUnresolved) {
        "DUPLICATE_UNRESOLVED"
    }

    fun moveTo(id: String, vararg steps: (ApprovalRequest) -> ApprovalRequest) = steps.forEach { step ->
        tx.execute { approvals.lock(id)!!.let { approvals.update(it, step(it.value)) } }
    }

    fun waitersOnApprovals(): Int = AdminDbAccounts.root(container, "performance_schema").use { c ->
        c.prepareStatement(
            "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w " +
                "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = 'admin_approval_request'",
        ).use { s ->
            s.setString(1, AdminDbAccounts.ADMIN_DATABASE)
            s.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

    fun rowsFor(paymentId: String): Long = AdminDbAccounts.root(container).use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM admin_approval_request WHERE target_kind = 'PAYMENT' AND target_ref = ?").use { s ->
            s.setString(1, paymentId)
            s.executeQuery().use { it.next(); it.getLong(1) }
        }
    }

    Given("two approval requests for the same Payment created concurrently") {
        Then("the second waits on the uncommitted first, then fails with DuplicateUnresolved (1062 on unresolved_key) — exactly one row") {
            val paymentId = newPayment()
            val firstInserted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<String?> {
                    tx.execute {
                        approvals.insert(newPending(paymentId))
                        firstInserted.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                    null
                }
                firstInserted.await(20, TimeUnit.SECONDS) shouldBe true
                val second = pool.submit<String?> { insert(newPending(paymentId)) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var waiting = 0
                while (System.nanoTime() < deadline && waiting < 1) {
                    waiting = waitersOnApprovals()
                    if (waiting < 1) Thread.sleep(50)
                }
                release.countDown()
                listOf(waiting, first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS), rowsFor(paymentId)) shouldBe listOf(1, null, "DUPLICATE_UNRESOLVED", 1L)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
        }
        Then("different Payments do not block each other") {
            listOf(insert(newPending(newPayment())), insert(newPending(newPayment()))) shouldBe listOf(null, null)
        }
    }

    Given("an unresolved approval in each non-terminal state") {
        Then("PENDING, EXECUTING and EXECUTION_UNKNOWN each block a new request for the Payment") {
            val pending = newPayment().also { insert(newPending(it)) }
            val executing = newPayment().also { p -> newPending(p).also { insert(it); moveTo(it.id, { r -> r.beginExecution(approver, "A", now) }) } }
            val unknown = newPayment().also { p ->
                newPending(p).also { insert(it); moveTo(it.id, { r -> r.beginExecution(approver, "A", now) }, { r -> r.executionUnknown() }) }
            }
            listOf(pending, executing, unknown).map { insert(newPending(it)) } shouldBe List(3) { "DUPLICATE_UNRESOLVED" }
        }
        Then("a terminal state (EXECUTED, OWNER_REJECTED from UNKNOWN, REJECTED, WITHDRAWN, INVALIDATED) frees the Payment for a new request") {
            val ends: List<(ApprovalRequest) -> List<(ApprovalRequest) -> ApprovalRequest>> = listOf(
                { _ -> listOf({ r -> r.beginExecution(approver, "A", now) }, { r -> r.executed() }) },
                { _ -> listOf({ r -> r.beginExecution(approver, "A", now) }, { r -> r.executionUnknown() }, { r -> r.ownerRejected("APPROVAL_EXECUTION_EXPIRED") }) },
                { _ -> listOf({ r -> r.rejected(approver, "A", now) }) },
                { _ -> listOf({ r -> r.withdrawn(requester, "Q", now) }) },
                { _ -> listOf({ r -> r.invalidated(approver, "A", "TARGET_CHANGED", now) }) },
            )
            ends.map { end ->
                val paymentId = newPayment()
                val request = newPending(paymentId)
                insert(request)
                moveTo(request.id, *end(request).toTypedArray())
                insert(newPending(paymentId))
            } shouldBe List(5) { null }
        }
    }

    // ACQ-ADMIN-001 B1-A 리뷰 m-3(c): 매입 원 파일 하나에 미해결 재매입 승인 하나(`unresolved_key = ACQFILE:<fileId>`, V7) — 같은 두 실제 연결 방식.
    val fileSeq = AtomicLong(System.nanoTime() % 100_000_000L)
    fun newFile() = "ACQ.R${fileSeq.incrementAndGet()}.TEST.20261105.001"
    fun newFileRequest(fileId: String): ApprovalRequest = ApprovalRequest.create(
        OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet()), ApprovalKind.ACQ_REACQUISITION, ApprovalTarget.AcquisitionFile(fileId),
        PinnedReacquisitionTarget(fileId, listOf(PinnedReacquisitionItem(1, "ptx_race1", "SALE", 0, "REJECTED", 10_000, 0, 10_000))).toSnapshotJson(),
        null, null, requester, "Q", now, Duration.ofHours(24),
    )
    fun rowsForFile(fileId: String): Long = AdminDbAccounts.root(container).use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM admin_approval_request WHERE target_kind = 'ACQUISITION_FILE' AND target_ref = ?").use { s ->
            s.setString(1, fileId)
            s.executeQuery().use { it.next(); it.getLong(1) }
        }
    }

    Given("two re-acquisition requests for the same original acquisition file created concurrently (B1-A review m-3(c))") {
        Then("the second waits on the uncommitted first, then fails with DuplicateUnresolved (1062 on ACQFILE key) — exactly one row; another file is not blocked") {
            val fileId = newFile()
            val firstInserted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<String?> {
                    tx.execute {
                        approvals.insert(newFileRequest(fileId))
                        firstInserted.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                    null
                }
                firstInserted.await(20, TimeUnit.SECONDS) shouldBe true
                val second = pool.submit<String?> { insert(newFileRequest(fileId)) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var waiting = 0
                while (System.nanoTime() < deadline && waiting < 1) {
                    waiting = waitersOnApprovals()
                    if (waiting < 1) Thread.sleep(50)
                }
                release.countDown()
                listOf(waiting, first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS), rowsForFile(fileId)) shouldBe listOf(1, null, "DUPLICATE_UNRESOLVED", 1L)
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
            insert(newFileRequest(newFile())) shouldBe null
        }
        Then("a terminal state (REJECTED) frees the file for a new request; EXECUTION_UNKNOWN still blocks it") {
            val freed = newFile().also { f -> newFileRequest(f).also { insert(it); moveTo(it.id, { r -> r.rejected(approver, "A", now) }) } }
            val unknown = newFile().also { f ->
                newFileRequest(f).also { insert(it); moveTo(it.id, { r -> r.beginExecution(approver, "A", now) }, { r -> r.executionUnknown() }) }
            }
            listOf(insert(newFileRequest(freed)), insert(newFileRequest(unknown))) shouldBe listOf(null, "DUPLICATE_UNRESOLVED")
        }
    }
})

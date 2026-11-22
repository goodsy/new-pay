package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.ApprovalRequestRepository
import com.paycore.admin.approval.application.required.OperatorCancelCommandStore
import com.paycore.admin.approval.application.required.OwnerExecutionRepository
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalStatus
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.OwnerServiceApprovalFixture
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.RowLockHolder
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * 소유 서비스 승인 영속화(ADMIN-PAYMENT-OPS-001 §4.11·§5 — 순수 domain ↔ `Jpa*Entity`, 실제 MySQL 8.4.11·runtime 계정 권한).
 * - 승인 행: Payment 대상(`target_kind`·`target_ref`, 계정 열 NULL)으로 저장·재구성, 결정 열만 갱신(열 권한), 미해결 키는 DB 생성 열.
 * - 명령 행: `@Immutable` INSERT-only, `pinned_target`(권위)과 승인 행 `target_snapshot`(표시용 사본)이 같은 JSON(nit).
 * - 실행 행: 불변 열(저장 본문 바이트·지문·기한·실행자)은 INSERT 뒤 바뀌지 않고, 갱신은 dirty checking으로 변경 열만 SET(`@Version`).
 * - 회복 선점(TZ-BIND-001): `lockDue(now)`의 `next_attempt_at ≤ now` 경계는 JVM 기본 시간대와 무관하게 UTC 기준이다.
 */
@SpringBootTest
@AdminDbTest
class OwnerServicePersistenceSpec(
    private val approvals: ApprovalRequestRepository,
    private val commands: OperatorCancelCommandStore,
    private val executions: OwnerExecutionRepository,
    transactionManager: PlatformTransactionManager,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val tx = TransactionTemplate(transactionManager)
    val seq = AtomicLong(System.nanoTime() % 1_000_000_000L * 1000)
    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    val requester = "adm_" + "R".repeat(26)
    val approver = "adm_" + "A".repeat(26)
    fun <T> inTx(block: () -> T): T = tx.execute { block() }!!
    fun root() = AdminDbAccounts.root(container)

    Given("a Payment-target OPERATOR_CORRECTION_CANCEL approval with its command") {
        val approvalId = OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet())
        val paymentId = OwnerServiceApprovalFixture.paymentId(seq.incrementAndGet())
        val request = OwnerServiceApprovalFixture.pending(approvalId, requester, now, paymentId, revision = 5)
        val command = OwnerServiceApprovalFixture.command(approvalId, paymentId, at = now, reason = "한글 사유\n둘째 줄", revision = 5)
        inTx {
            approvals.insert(request)
            commands.insert(command)
        }

        Then("the approval row stores a PAYMENT target (target_ref, no account) and reconstitutes to the same request") {
            val loaded = inTx { approvals.find(approvalId)!! }
            listOf(loaded.kind, loaded.target, loaded.status, loaded.requestedBy, loaded.expiresAt, loaded.targetSnapshot.isNotEmpty()) shouldBe
                listOf(ApprovalKind.OPERATOR_CORRECTION_CANCEL, ApprovalTarget.Payment(paymentId, 5), ApprovalStatus.PENDING, requester, request.expiresAt, true)
            root().use { c ->
                c.column(
                    "SELECT CONCAT(target_kind, '|', COALESCE(target_actor_id, '-'), '|', target_ref, '|', target_security_version, '|', unresolved_key, '|', COALESCE(pending_key, '-')) " +
                        "FROM admin_approval_request WHERE approval_id = ?",
                    approvalId,
                )
            } shouldBe listOf("PAYMENT|-|$paymentId|5|PAYMENT:$paymentId|-")
        }

        Then("the 0-step immutable columns of a Payment row carry the target kind and reference; the account accessor fails closed (M-6)") {
            val columns = inTx { approvals.immutableColumns(approvalId)!! }
            listOf(columns.kind, columns.targetKind, columns.targetRef, columns.grantedRole) shouldBe listOf("OPERATOR_CORRECTION_CANCEL", "PAYMENT", paymentId, null)
            shouldThrow<IllegalStateException> { columns.targetActorId }
        }

        Then("the command reconstitutes to the same snapshot; pinned_target equals the approval's target_snapshot copy (nit)") {
            inTx { commands.find(approvalId) } shouldBe command
            inTx { commands.find(approvalId)!!.let { listOf(it.reason, it.purposeReference, it.pinnedTarget, it.amountMinor) } } shouldBe
                listOf("한글 사유\n둘째 줄", "RECON-2026-0001", command.pinnedTarget, 7_000L)
            root().use { c ->
                c.scalar(
                    "SELECT c.pinned_target = r.target_snapshot FROM admin_operator_cancel_command c JOIN admin_approval_request r ON r.approval_id = c.approval_id " +
                        "WHERE c.approval_id = ?",
                    approvalId,
                ).toString()
            } shouldBe "1"
        }

        Then("approval TX1 → EXECUTING with an execution row; TX2 → EXECUTION_UNKNOWN; the result → EXECUTED releases the unresolved key") {
            val execution = OwnerServiceApprovalFixture.execution(approvalId, approver, now, body = "{\"approvalId\":\"$approvalId\",\"reason\":\"한글\"}".toByteArray())
            inTx {
                val locked = approvals.lock(approvalId)!!
                approvals.update(locked, locked.value.beginExecution(approver, "Approver", now))
                executions.insert(execution)
            }
            inTx {
                val locked = approvals.lock(approvalId)!!
                approvals.update(locked, locked.value.executionUnknown())
                val row = executions.lock(approvalId)!!
                executions.update(row, row.value.unknownResponse(now.plusSeconds(1), "TIMEOUT", now.plusSeconds(300)).flagIntegrity("APPROVAL_CONFLICT"))
            }
            val unknown = inTx { listOf(approvals.find(approvalId)!!.status, executions.find(approvalId)!!.let { listOf(it.lastFailure, it.integrityFlag, it.nextAttemptAt, it.attemptCount) }) }
            unknown shouldBe listOf(ApprovalStatus.EXECUTION_UNKNOWN, listOf("TIMEOUT", "APPROVAL_CONFLICT", now.plusSeconds(300), 1))
            inTx {
                val locked = approvals.lock(approvalId)!!
                approvals.update(locked, locked.value.executed())
                val row = executions.lock(approvalId)!!
                executions.update(row, row.value.ownerResult(now.plusSeconds(2), null, "pcn_01JC0000000000000000000001"))
            }
            val loaded = inTx { executions.find(approvalId)!! }
            listOf(loaded.ownerReference, loaded.requestBody().toList(), loaded.fingerprint, loaded.executeBefore, loaded.executorActorId) shouldBe
                listOf("pcn_01JC0000000000000000000001", execution.requestBody().toList(), execution.fingerprint, execution.executeBefore, approver)
            root().use { c ->
                c.column(
                    "SELECT CONCAT(r.status, '|', r.decided_by, '|', COALESCE(r.unresolved_key, '-'), '|', e.version, '|', HEX(e.request_body) = HEX(?)) " +
                        "FROM admin_approval_request r JOIN admin_owner_execution e ON e.approval_id = r.approval_id WHERE r.approval_id = ?",
                    execution.requestBody(), approvalId,
                )
            } shouldBe listOf("EXECUTED|$approver|-|2|1")
        }
    }

    Given("an execution row update (dirty checking — only the changed mutable columns)") {
        Then("a stale expected version is an optimistic conflict; the stored body never changes") {
            val approvalId = OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet())
            inTx {
                approvals.insert(OwnerServiceApprovalFixture.pending(approvalId, requester, now, OwnerServiceApprovalFixture.paymentId(seq.incrementAndGet())))
                val locked = approvals.lock(approvalId)!!
                approvals.update(locked, locked.value.beginExecution(approver, "Approver", now))
                executions.insert(OwnerServiceApprovalFixture.execution(approvalId, approver, now))
            }
            val first = inTx { executions.lock(approvalId)!! }
            inTx { executions.update(executions.lock(approvalId)!!, first.value.claimed(now.plusSeconds(5), Duration.ofSeconds(30))) }
            shouldThrow<com.paycore.admin.approval.application.required.ApprovalPersistenceFailure.OptimisticConflict> {
                inTx { executions.update(first, first.value.claimed(now.plusSeconds(6), Duration.ofSeconds(30))) }
            }
            inTx { executions.find(approvalId)!!.attemptCount } shouldBe 2
        }
    }

    Given("TZ-BIND-001: the recovery claim boundary next_attempt_at <= now (UTC) does not depend on the JVM default time zone") {
        Then("under Asia/Seoul and America/Los_Angeles a row due 1 minute before now is claimed and a row due 1 minute after now is not") {
            fun executingApproval(): String {
                val approvalId = OwnerServiceApprovalFixture.approvalId(seq.incrementAndGet())
                inTx {
                    approvals.insert(OwnerServiceApprovalFixture.pending(approvalId, requester, now, OwnerServiceApprovalFixture.paymentId(seq.incrementAndGet())))
                    val locked = approvals.lock(approvalId)!!
                    approvals.update(locked, locked.value.beginExecution(approver, "Approver", now))
                    executions.insert(OwnerServiceApprovalFixture.execution(approvalId, approver, now))
                }
                return approvalId
            }
            val utcLiteral = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
            fun nextAttemptAt(approvalId: String, at: Instant) =
                root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = ? WHERE approval_id = ?", utcLiteral.format(at), approvalId) }
            val dueJustBefore = executingApproval()
            val dueJustAfter = executingApproval()
            val original = TimeZone.getDefault()
            // 끝나면 두 행을 먼 미래로 미뤄 둔다 — 같은 컨텍스트를 쓰는 뒤 시험의 회복 선점에 섞이지 않게(리뷰 MINOR-1).
            val claimedByZone = try { listOf("Asia/Seoul", "America/Los_Angeles").associateWith { zone ->
                val claimNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                nextAttemptAt(dueJustBefore, claimNow.minusSeconds(60))
                nextAttemptAt(dueJustAfter, claimNow.plusSeconds(60))
                // 다른 시험이 남긴 실행 행(일부러 깨뜨린 행 포함)은 세 번째 연결이 PK로 잡아 둬 SKIP LOCKED로 빠지게 한다 — 이 두 행의 경계만 본다.
                val others = root().use { it.column("SELECT approval_id FROM admin_owner_execution WHERE approval_id NOT IN (?, ?)", dueJustBefore, dueJustAfter) }
                RowLockHolder.open(container).use { holder ->
                    others.forEach { holder.lock("admin_owner_execution", "approval_id", it) }
                    try {
                        TimeZone.setDefault(TimeZone.getTimeZone(zone))
                        val claimed = inTx { executions.lockDue(claimNow, 10).map { it.value.approvalId }.toSet() }
                        listOf(dueJustBefore in claimed, dueJustAfter in claimed)
                    } finally {
                        TimeZone.setDefault(original)
                        holder.release()
                    }
                }
            } } finally {
                val parked = Instant.parse("2999-01-01T00:00:00Z")
                nextAttemptAt(dueJustBefore, parked)
                nextAttemptAt(dueJustAfter, parked)
            }
            claimedByZone shouldBe mapOf("Asia/Seoul" to listOf(true, false), "America/Los_Angeles" to listOf(true, false))
        }
    }
})

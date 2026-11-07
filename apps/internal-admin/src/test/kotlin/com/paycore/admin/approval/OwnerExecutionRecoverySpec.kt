package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.application.provided.AccountCommandResult
import com.paycore.admin.account.application.provided.AdminAccountCommandUseCase
import com.paycore.admin.account.application.provided.RevokeAdminRole
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.OwnerOutcomeApplier
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.paymentops.application.provided.PaymentOpsPolicy
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 회복 job(ADMIN-PAYMENT-OPS-001 §4.16·§7.2 — `OwnerExecutionRecoverySpec`·`IntegrityFlagNoResendSpec`·`ResendHaltOnIneligibleParticipantSpec`·`DeadlineConfirmationRecoverySpec`·
 * `DecisionContentCheckSpec`·`OwnerExecutionClaimRaceSpec`·`OutcomeDivergenceSpec`·`Tx2AttemptFieldsSpec`). 실제 MySQL 8.4.11, PAYMENT는 실제 HTTP 대역.
 * 미해결 승인은 공식 경로(요청 → 승인 → PAYMENT 503 → EXECUTION_UNKNOWN)로 만들고, 시간 경과만 root로 앞당긴다(`next_attempt_at`을 과거로, 기한 시험은 승인·기한
 * 시각을 과거로 — CHECK `execute_before > approved_at` 유지). 다른 시험이 남긴 행과 섞이지 않게 대상 행은 다음 시도를 2000년으로 둬 선점 순서 맨 앞에 온다.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class OwnerExecutionRecoverySpec(
    owners: OwnerServiceApprovalUseCase,
    private val path: ApprovalPath,
    ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
    private val recovery: RecoverOwnerExecutionsUseCase,
    private val applier: OwnerOutcomeApplier,
    private val policy: PaymentOpsPolicy,
    private val commands: AdminAccountCommandUseCase,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    val fixture = OwnerApprovalFixture(owners, path, ready, storage)
    fun root() = AdminDbAccounts.root(container)

    class Unknown(val id: String, val paymentId: String, val requester: String, val approver: String, val fingerprint: String)

    /** 공식 경로로 EXECUTION_UNKNOWN 승인을 만든다(승인 POST에 PAYMENT 503). [flag]면 409 APPROVAL_CONFLICT(무결성 플래그). */
    fun unknown(flag: Boolean = false): Unknown {
        val operator = fixture.operator()
        val (id, paymentId) = fixture.requested(operator)
        StubPaymentOpsServer.execution(
            id, if (flag) StubPaymentOpsServer.Reply(409, "{\"error\":\"APPROVAL_CONFLICT\"}") else StubPaymentOpsServer.Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}"),
        )
        val approver = fixture.approver()
        fixture.approve(id, approver) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
        val fp = root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) as String }
        return Unknown(id, paymentId, operator.id.value, approver.account.id.value, fp)
    }
    fun due(id: String) = root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
    fun pastDeadline(id: String, minutesAgo: Long = 60) = root().use {
        it.update(
            "UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 DAY, execute_before = UTC_TIMESTAMP(3) - INTERVAL ? MINUTE WHERE approval_id = ?",
            minutesAgo, id,
        )
    }
    fun row(id: String) = root().use {
        it.column(
            "SELECT CONCAT(r.status, '|', COALESCE(r.result_code, '-'), '|', COALESCE(r.unresolved_key, '-'), '|', e.attempt_count, '|', COALESCE(e.owner_reference, '-'), '|', " +
                "COALESCE(e.integrity_flag, '-'), '|', COALESCE(e.resend_halted_reason, '-')) FROM admin_approval_request r JOIN admin_owner_execution e ON e.approval_id = r.approval_id " +
                "WHERE r.approval_id = ?",
            id,
        ).single()
    }
    fun posts(id: String) = StubPaymentOpsServer.requestsFor(id).count { it.method == "POST" && it.path == "/internal/v1/admin/operator-cancellations" }
    fun decisionGets(id: String) = StubPaymentOpsServer.requestsForPath("/internal/v1/admin/operator-cancellations/$id").size
    fun deadlineCalls(id: String) = StubPaymentOpsServer.requestsForPath("/internal/v1/admin/operator-cancellations/$id/deadline-confirmation")
    fun storedBody(id: String): List<Byte> = root().use { c ->
        c.prepareStatement("SELECT request_body FROM admin_owner_execution WHERE approval_id = ?").use { s -> s.setString(1, id); s.executeQuery().use { r -> r.next(); r.getBytes(1).toList() } }
    }
    fun started(u: Unknown, cid: String, amount: Long = 7_000) =
        StubPaymentOpsServer.Reply(200, StubPaymentOpsServer.decisionBody(u.paymentId, amount, u.requester, u.approver, u.fingerprint, cancellationId = cid))
    fun rejected(u: Unknown, code: String, confirmed: Boolean? = null) =
        StubPaymentOpsServer.Reply(200, StubPaymentOpsServer.decisionBody(u.paymentId, 7_000, u.requester, u.approver, u.fingerprint, code = code, confirmed = confirmed))
    fun counter(name: String, vararg tags: String) = registry.find(name).tags(*tags).counters().sumOf { it.count() }
    fun revoke(actorId: String, role: String) =
        commands.revokeRole(RevokeAdminRole(storage.securityAdmin().id.value, actorId, role, path.context())).shouldBeInstanceOf<AccountCommandResult.Succeeded>()

    Given("the backoff policy (I-22)") {
        Then("30 s × 2^(n−1) capped at backoff-max (test configuration 1 minute — the Demo 10-minute cap is PaymentOpsDecisionGoldenSpec), with no attempt limit") {
            listOf(1, 2, 3, 50, 10_000).map { policy.backoff(it) } shouldBe listOf(30L, 60, 60, 60, 60).map(Duration::ofSeconds)
        }
    }

    Given("an EXECUTION_UNKNOWN approval that is not due yet") {
        Then("the next attempt is at least approval + stale (connect + read + 30 s) — the job does not claim it while the web thread could still apply TX2") {
            val u = unknown()
            (root().use { (it.scalar("SELECT TIMESTAMPDIFF(SECOND, approved_at, next_attempt_at) FROM admin_owner_execution WHERE approval_id = ?", u.id) as Number).toLong() } >= 33) shouldBe true
            recovery.recoverDue()
            listOf(row(u.id), decisionGets(u.id).toString()) shouldBe listOf("EXECUTION_UNKNOWN|-|PAYMENT:${u.paymentId}|1|-|-|-", "0")
        }
    }

    Given("a due EXECUTION_UNKNOWN approval (recovery order: recorded decision first)") {
        Then("a recorded STARTED decision with matching content → EXECUTED without any resend; the Payment is released") {
            val u = unknown()
            StubPaymentOpsServer.decision(u.id, started(u, "pcn_01JC000000000000000000RC01"))
            due(u.id)
            recovery.recoverDue()
            listOf(row(u.id), posts(u.id).toString(), decisionGets(u.id).toString()) shouldBe
                listOf("EXECUTED|-|-|2|pcn_01JC000000000000000000RC01|-|-", "1", "1")
        }
        Then("a recorded REJECTED decision → OWNER_REJECTED(code), the unresolved key is released and a new request for the Payment succeeds") {
            val u = unknown()
            StubPaymentOpsServer.decision(u.id, rejected(u, "CANCEL_IN_PROGRESS"))
            due(u.id)
            recovery.recoverDue()
            row(u.id) shouldBe "OWNER_REJECTED|CANCEL_IN_PROGRESS|-|2|-|-|-"
            StubPaymentOpsServer.targetFound(u.paymentId)
            (fixture.request(fixture.operator(), u.paymentId) is ApprovalOutcome.Created) shouldBe true
        }
        Then("no decision and before the deadline → the same stored body bytes are resent (no regeneration); 200 → EXECUTED; resend counted") {
            val u = unknown()
            StubPaymentOpsServer.execution(u.id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000RS01\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
            due(u.id)
            val before = counter("paycore.admin.ops.resend", "result", "EXECUTE_STARTED")
            recovery.recoverDue()
            val resent = StubPaymentOpsServer.requestsFor(u.id).filter { it.method == "POST" && it.path == "/internal/v1/admin/operator-cancellations" }
            resent.size shouldBe 2
            resent.map { it.body.toList() }.toSet() shouldBe setOf(storedBody(u.id))
            resent.last().signatureValid shouldBe true
            row(u.id) shouldBe "EXECUTED|-|-|2|pcn_01JC000000000000000000RS01|-|-"
            (counter("paycore.admin.ops.resend", "result", "EXECUTE_STARTED") - before) shouldBe 1.0
        }
        Then("the resend still fails (503) → stays EXECUTION_UNKNOWN, next attempt = now + backoff(2) = 60 s, no attempt limit") {
            val u = unknown()
            due(u.id)
            recovery.recoverDue()
            row(u.id) shouldBe "EXECUTION_UNKNOWN|-|PAYMENT:${u.paymentId}|2|-|-|-"
            (root().use { (it.scalar("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), next_attempt_at) FROM admin_owner_execution WHERE approval_id = ?", u.id) as Number).toLong() } in 50..70) shouldBe true
        }
        Then("the decision lookup is unavailable → no resend this cycle, state unchanged (D-OPS-003: only a recorded decision ends it)") {
            val u = unknown()
            StubPaymentOpsServer.decision(u.id, StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            due(u.id)
            recovery.recoverDue()
            listOf(row(u.id), posts(u.id).toString()) shouldBe listOf("EXECUTION_UNKNOWN|-|PAYMENT:${u.paymentId}|2|-|-|-", "1")
        }
    }

    Given("DecisionContentCheckSpec (L-b, I-35): the recorded decision's content differs from the ADMIN approval") {
        Then("the integrity flag DECISION_CONTENT_MISMATCH is set first, alerted and audited, and the decision is still applied (EXECUTED with the flag)") {
            val u = unknown()
            StubPaymentOpsServer.decision(u.id, started(u, "pcn_01JC000000000000000000MM01", amount = 6_999))
            due(u.id)
            val alerts = counter("paycore.admin.ops.integrity_alert", "code", "DECISION_CONTENT_MISMATCH")
            recovery.recoverDue()
            row(u.id) shouldBe "EXECUTED|-|-|2|pcn_01JC000000000000000000MM01|DECISION_CONTENT_MISMATCH|-"
            (counter("paycore.admin.ops.integrity_alert", "code", "DECISION_CONTENT_MISMATCH") - alerts) shouldBe 1.0
            root().use { it.column("SELECT CONCAT(actor_kind, '|', target_ref) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_INTEGRITY_MISMATCH'", u.id) } shouldBe
                listOf("SYSTEM_JOB|${u.paymentId}")
        }
    }

    Given("IntegrityFlagNoResendSpec (D-OPS-007): an approval flagged by APPROVAL_CONFLICT") {
        Then("no decision → no POST again (lookup only); a later recorded decision is applied with the flag kept") {
            val u = unknown(flag = true)
            due(u.id)
            recovery.recoverDue()
            listOf(posts(u.id).toString(), decisionGets(u.id).toString(), row(u.id)) shouldBe
                listOf("1", "1", "EXECUTION_UNKNOWN|-|PAYMENT:${u.paymentId}|2|-|APPROVAL_CONFLICT|-")
            StubPaymentOpsServer.decision(u.id, started(u, "pcn_01JC000000000000000000FG01"))
            due(u.id)
            recovery.recoverDue()
            listOf(posts(u.id).toString(), row(u.id)) shouldBe listOf("1", "EXECUTED|-|-|3|pcn_01JC000000000000000000FG01|APPROVAL_CONFLICT|-")
        }
    }

    Given("ResendHaltOnIneligibleParticipantSpec (D-OPS-006, I-26)") {
        Then("the requester lost OPERATOR: no resend, UNKNOWN kept, halted once (alert + SYSTEM_JOB audit), lookups continue, no resume after the role is granted back") {
            val u = unknown()
            revoke(u.requester, "OPERATOR")
            val halts = counter("paycore.admin.ops.resend_halted", "reason", "REQUESTER_NOT_ELIGIBLE")
            due(u.id)
            recovery.recoverDue()
            due(u.id)
            recovery.recoverDue()
            listOf(posts(u.id).toString(), decisionGets(u.id).toString(), row(u.id)) shouldBe
                listOf("1", "2", "EXECUTION_UNKNOWN|-|PAYMENT:${u.paymentId}|3|-|-|REQUESTER_NOT_ELIGIBLE")
            (counter("paycore.admin.ops.resend_halted", "reason", "REQUESTER_NOT_ELIGIBLE") - halts) shouldBe 1.0
            root().use { it.column("SELECT CONCAT(actor_kind, '|', target_ref) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_RESEND_HALTED'", u.id) } shouldBe
                listOf("SYSTEM_JOB|${u.paymentId}")
            path.grant(com.paycore.admin.account.domain.AdminActorId(u.requester), AdminRole.OPERATOR)
            due(u.id)
            recovery.recoverDue()
            posts(u.id) shouldBe 1
        }
        Then("the approver lost APPROVER: no resend, halted with APPROVER_NOT_ELIGIBLE") {
            val u = unknown()
            revoke(u.approver, "APPROVER")
            due(u.id)
            recovery.recoverDue()
            listOf(posts(u.id).toString(), row(u.id)) shouldBe listOf("1", "EXECUTION_UNKNOWN|-|PAYMENT:${u.paymentId}|2|-|-|APPROVER_NOT_ELIGIBLE")
        }
    }

    Given("DeadlineConfirmationRecoverySpec (D-OPS-009, I-34)") {
        Then("① halted + no decision + deadline + skew passed → deadline confirmation with the stored body (no execute POST) → OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED), Payment released") {
            val u = unknown()
            revoke(u.requester, "OPERATOR")
            StubPaymentOpsServer.deadline(u.id, rejected(u, "APPROVAL_EXECUTION_EXPIRED", confirmed = true))
            pastDeadline(u.id)
            due(u.id)
            recovery.recoverDue()
            row(u.id) shouldBe "OWNER_REJECTED|APPROVAL_EXECUTION_EXPIRED|-|2|-|-|-"
            listOf(posts(u.id).toString(), deadlineCalls(u.id).size.toString()) shouldBe listOf("1", "1")
            deadlineCalls(u.id).single().body.toList() shouldBe storedBody(u.id)
            StubPaymentOpsServer.targetFound(u.paymentId)
            (fixture.request(fixture.operator(), u.paymentId) is ApprovalOutcome.Created) shouldBe true
        }
        Then("② the deadline passed but not the skew (ADMIN clock) → no deadline call, a normal resend") {
            val u = unknown()
            pastDeadline(u.id, minutesAgo = 1)
            due(u.id)
            recovery.recoverDue()
            listOf(deadlineCalls(u.id).size.toString(), posts(u.id).toString()) shouldBe listOf("0", "2")
        }
        Then("③ PAYMENT answers NONE (its clock is before the deadline) → state kept, no execute POST, checked again next cycle") {
            val u = unknown()
            pastDeadline(u.id)
            due(u.id)
            recovery.recoverDue()
            listOf(row(u.id), posts(u.id).toString(), deadlineCalls(u.id).size.toString()) shouldBe
                listOf("EXECUTION_UNKNOWN|-|PAYMENT:${u.paymentId}|2|-|-|-", "1", "1")
        }
        Then("④ the deadline confirmation returns an existing STARTED decision → EXECUTED") {
            val u = unknown()
            StubPaymentOpsServer.deadline(u.id, started(u, "pcn_01JC000000000000000000DM04"))
            pastDeadline(u.id)
            due(u.id)
            recovery.recoverDue()
            row(u.id) shouldBe "EXECUTED|-|-|2|pcn_01JC000000000000000000DM04|-|-"
        }
        Then("⑤ a flagged approval past the deadline is also closed by the deadline confirmation") {
            val u = unknown(flag = true)
            StubPaymentOpsServer.deadline(u.id, rejected(u, "APPROVAL_EXECUTION_EXPIRED", confirmed = true))
            pastDeadline(u.id)
            due(u.id)
            recovery.recoverDue()
            listOf(row(u.id), posts(u.id).toString()) shouldBe listOf("OWNER_REJECTED|APPROVAL_EXECUTION_EXPIRED|-|2|-|APPROVAL_CONFLICT|-", "1")
        }
    }

    Given("OwnerExecutionClaimRaceSpec: two job instances recover at the same time (SKIP LOCKED)") {
        Then("each due approval is claimed and processed exactly once") {
            val us = (1..3).map { unknown() }
            us.forEach { due(it.id) }
            val pool = Executors.newFixedThreadPool(2)
            listOf(Callable { recovery.recoverDue() }, Callable { recovery.recoverDue() }).map(pool::submit).forEach { it.get(60, TimeUnit.SECONDS) }
            pool.shutdownNow()
            us.map { listOf(decisionGets(it.id), posts(it.id)) } shouldBe List(3) { listOf(1, 2) }
            us.map { row(it.id).split('|')[3] } shouldBe List(3) { "2" }
        }
    }

    Given("S6 review L-1: audit volume of the job (I-112)") {
        Then("a lookup failure repeating the same classification is audited once (then last_failure + metrics only); a changed classification adds one row") {
            val u = unknown()
            fun lookupAudits() = root().use {
                (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_EXECUTION_UNKNOWN' AND detail LIKE '%reason=DECISION_%'", u.id) as Number).toLong()
            }
            StubPaymentOpsServer.decision(u.id, StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            val counts = mutableListOf<Long>()
            repeat(2) {
                due(u.id)
                recovery.recoverDue()
                counts += lookupAudits()
            }
            StubPaymentOpsServer.decision(u.id, StubPaymentOpsServer.Reply(500, "{\"error\":\"X\"}"))
            due(u.id)
            recovery.recoverDue()
            counts += lookupAudits()
            counts shouldBe listOf(1L, 1L, 2L)
            root().use { it.scalar("SELECT last_failure FROM admin_owner_execution WHERE approval_id = ?", u.id) } shouldBe "DECISION_HTTP_500"
        }
        Then("every resend (an external effect) is audited each time, even with the same failure classification") {
            val u = unknown()
            fun resendAudits() = root().use {
                (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_EXECUTION_UNKNOWN' AND actor_kind = 'SYSTEM_JOB'", u.id) as Number).toLong()
            }
            repeat(2) {
                due(u.id)
                recovery.recoverDue()
            }
            resendAudits() shouldBe 2L
        }
    }

    Given("S6 review L-2: one row failing must not stop the others") {
        Then("an execution row without its command is an integrity violation (metric + separate-TX audit) and an unreadable row counts resend{ERROR}; the other due row is processed") {
            val missing = unknown()
            val broken = unknown()
            val healthy = unknown()
            root().use {
                it.update("SET FOREIGN_KEY_CHECKS = 0")
                it.update("DELETE FROM admin_operator_cancel_command WHERE approval_id = ?", missing.id)
                it.update("SET FOREIGN_KEY_CHECKS = 1")
                it.update("UPDATE admin_operator_cancel_command SET pinned_target = JSON_OBJECT('paymentId', 1) WHERE approval_id = ?", broken.id)
            }
            listOf(missing, broken, healthy).forEach { due(it.id) }
            val violations = counter("paycore.admin.approval.integrity_violation", "check", "command")
            val errors = counter("paycore.admin.ops.resend", "result", "ERROR")
            recovery.recoverDue()
            (counter("paycore.admin.approval.integrity_violation", "check", "command") - violations) shouldBe 1.0
            (counter("paycore.admin.ops.resend", "result", "ERROR") - errors) shouldBe 1.0
            root().use { it.column("SELECT CONCAT(actor_kind, '|', detail, '|', target_ref) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_INTEGRITY_VIOLATION'", missing.id) } shouldBe
                listOf("SYSTEM_JOB|column=command|${missing.paymentId}")
            listOf(decisionGets(healthy.id), posts(healthy.id)) shouldBe listOf(1, 2)
        }
    }

    Given("ACQ-ADMIN-001 rev 0.6 (B1-A re-review NIT — symmetric with the re-acquisition branch, I-25 ③): an operator cancel execution row whose fingerprint prefix is not its kind's") {
        Then("the job calls PAYMENT neither for the decision lookup nor for a resend; it records an integrity violation (column=execution_kind) and the approval stays EXECUTION_UNKNOWN") {
            val u = unknown()
            root().use { it.update("UPDATE admin_owner_execution SET fingerprint = CONCAT('acd1', SUBSTRING(fingerprint, 5)) WHERE approval_id = ?", u.id) }
            due(u.id)
            val violations = counter("paycore.admin.approval.integrity_violation", "check", "execution_kind")
            recovery.recoverDue()
            listOf(row(u.id).substringBefore("|PAYMENT:"), decisionGets(u.id).toString(), posts(u.id).toString(), deadlineCalls(u.id).size.toString()) shouldBe
                listOf("EXECUTION_UNKNOWN|-", "0", "1", "0")
            (counter("paycore.admin.approval.integrity_violation", "check", "execution_kind") - violations) shouldBe 1.0
            root().use {
                it.column("SELECT CONCAT(actor_kind, '|', detail, '|', target_ref) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_INTEGRITY_VIOLATION'", u.id)
            } shouldBe listOf("SYSTEM_JOB|column=execution_kind|${u.paymentId}")
        }
    }

    Given("SKIP LOCKED: a due row held by another instance (third connection holds its execution row)") {
        Then("the job does not wait for it: it returns promptly, processes the other due row, and leaves the held one for a later run") {
            val held = unknown()
            val free = unknown()
            due(held.id)
            due(free.id)
            val elapsedMs = com.paycore.admin.support.RowLockHolder.open(container).use { holder ->
                holder.lock("admin_owner_execution", "approval_id", held.id)
                val started = System.nanoTime()
                val running = java.util.concurrent.CompletableFuture.supplyAsync { recovery.recoverDue() }
                try {
                    running.get(8, TimeUnit.SECONDS)
                } finally {
                    holder.release()
                    running.get(60, TimeUnit.SECONDS)
                }
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            }
            (elapsedMs < 8_000) shouldBe true
            listOf(decisionGets(held.id), decisionGets(free.id)) shouldBe listOf(0, 1)
        }
    }

    Given("S7 review L-1: due rows are claimed one at a time, just before each is processed (no batch claim)") {
        Then("while the first due row is being processed, the second due row is not yet claimed (attempt count unchanged); afterwards each was claimed and processed once") {
            val first = unknown()
            val second = unknown()
            root().use {
                it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", first.id)
                it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.001' WHERE approval_id = ?", second.id)
            }
            StubPaymentOpsServer.decision(first.id, StubPaymentOpsServer.Reply(200, "{\"decision\":\"NONE\"}", delayMillis = 1_500))
            fun attempts(id: String) = row(id).split('|')[3]
            val running = java.util.concurrent.CompletableFuture.supplyAsync { recovery.recoverDue() }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (decisionGets(first.id) == 0 && System.nanoTime() < until) Thread.sleep(20)
            val whileFirst = listOf(decisionGets(first.id), attempts(first.id), attempts(second.id))
            running.get(60, TimeUnit.SECONDS)
            whileFirst shouldBe listOf(1, "2", "1")
            listOf(attempts(first.id), attempts(second.id), decisionGets(second.id), posts(second.id)) shouldBe listOf("2", "2", 1, 2)
        }
    }

    Given("OutcomeDivergenceSpec and Tx2AttemptFieldsSpec (§4.13 D, L-e)") {
        Then("a different result for an already EXECUTED approval changes nothing and is counted as an outcome divergence; the same result is not") {
            val operator = fixture.operator()
            val (id, _) = fixture.requested(operator)
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000DV01\",\"status\":\"IN_PROGRESS\",\"existing\":false}"))
            fixture.approve(id) shouldBe ApprovalOutcome.Executed(id)
            val before = counter("paycore.admin.ops.outcome_divergence")
            applier.apply(id, OwnerOutcomeApplier.Outcome.Started("pcn_01JC000000000000000000DV01"), OwnerOutcomeApplier.Source.Job)
            (counter("paycore.admin.ops.outcome_divergence") - before) shouldBe 0.0
            applier.apply(id, OwnerOutcomeApplier.Outcome.Rejected("CANCEL_IN_PROGRESS"), OwnerOutcomeApplier.Source.Job)
            (counter("paycore.admin.ops.outcome_divergence") - before) shouldBe 1.0
            root().use { it.column("SELECT status FROM admin_approval_request WHERE approval_id = ?", id) } shouldBe listOf("EXECUTED")
        }
        Then("a late UNKNOWN result never pulls the job's next attempt earlier and never changes the attempt count") {
            val u = unknown()
            root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = UTC_TIMESTAMP(3) + INTERVAL 10 MINUTE WHERE approval_id = ?", u.id) }
            applier.apply(u.id, OwnerOutcomeApplier.Outcome.Unknown("TIMEOUT", null), OwnerOutcomeApplier.Source.Job)
            root().use {
                it.column("SELECT CONCAT(attempt_count, '|', TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), next_attempt_at) >= 500, '|', last_failure) FROM admin_owner_execution WHERE approval_id = ?", u.id)
            } shouldBe listOf("1|1|TIMEOUT")
        }
    }

    Given("S5 review L-A: an internal integrity violation inside the web TX2") {
        Then("it is not buried in a log: counted and audited in a separate TX with the approval id; the approval stays EXECUTING for the job") {
            val (id, paymentId) = fixture.requested()
            StubPaymentOpsServer.execution(id, StubPaymentOpsServer.Reply(200, "{\"cancellationId\":\"pcn_01JC000000000000000000MA01\",\"status\":\"IN_PROGRESS\",\"existing\":false}", delayMillis = 1_000))
            val violations = counter("paycore.admin.approval.integrity_violation", "check", "execution")
            val running = java.util.concurrent.CompletableFuture.supplyAsync { fixture.approve(id) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (StubPaymentOpsServer.requestsFor(id).none { it.method == "POST" } && System.nanoTime() < deadline) Thread.sleep(10)
            root().use { it.update("DELETE FROM admin_owner_execution WHERE approval_id = ?", id) }
            running.get(30, TimeUnit.SECONDS) shouldBe ApprovalOutcome.Executing(id, "EXECUTING")
            (counter("paycore.admin.approval.integrity_violation", "check", "execution") - violations) shouldBe 1.0
            root().use { it.column("SELECT CONCAT(detail, '|', target_ref) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_INTEGRITY_VIOLATION'", id) } shouldBe
                listOf("column=execution|$paymentId")
        }
    }

    Given("the backlog gauges (§4.16)") {
        Then("after a recovery run the unresolved gauge counts EXECUTION_UNKNOWN approvals and the oldest UNKNOWN age is reported") {
            unknown()
            recovery.recoverDue()
            ((registry.find("paycore.admin.ops.unresolved").tags("status", "EXECUTION_UNKNOWN").gauge()?.value() ?: 0.0) >= 1.0) shouldBe true
            ((registry.find("paycore.admin.ops.unknown.oldest_age_seconds").gauge()?.value() ?: -1.0) >= 0.0) shouldBe true
        }
    }
})

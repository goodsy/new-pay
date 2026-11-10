package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer

/**
 * 가맹점 계약 승인 회복(MERCHANT-OPS-002 §8.2 B2-2-10 — §9.5 0~5, C-18·C-28 — PAYMENT·FINANCIAL 회복과 같은 순서). 실제 MySQL 8.4 + MASTER 실제 HTTP 대역. 시간 경과는 root로
 * 실행 행 시각만 앞당긴다(OwnerExecutionRecoverySpec과 같은 방식). 호출 순서는 대역이 받은 요청 경로로 단언한다(조회 → 기한 확인 → 실행).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantOpsRecoverySpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    registry: MeterRegistry,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val f = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    f.install()
    afterSpec { f.uninstall() }
    fun root() = AdminDbAccounts.root(container)
    fun alerts(code: String) = registry.find("paycore.admin.ops.integrity_alert").tags("code", code).counters().sumOf { it.count() }
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun execution(id: String) = root().use {
        it.scalar(
            "SELECT CONCAT(COALESCE(owner_reference, '-'), '|', COALESCE(owner_result_code, '-'), '|', COALESCE(integrity_flag, '-'), '|', COALESCE(resend_halted_reason, '-')) " +
                "FROM admin_owner_execution WHERE approval_id = ?",
            id,
        ) as String
    }
    fun findings(id: String) = root().use { it.column("SELECT CONCAT(kind, '|', COALESCE(payment_ref, '-'), '|', COALESCE(detail_code, '-')) FROM admin_ops_reconciliation_finding WHERE approval_id = ?", id) }
    fun audits(id: String, action: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE approval_id = ? AND action = ?", id, action) as Number).toLong() }
    /** 대역이 이 승인에 대해 받은 호출 경로 순서(E = 실행, D = 결정 조회, C = 기한 확인). */
    fun calls(id: String): String = StubMasterOpsServer.received.filter {
        it.path in setOf(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS, StubMasterOpsServer.APPROVAL_DECISIONS, StubMasterOpsServer.DEADLINE_CONFIRMATIONS) && f.idOf(it.body) == id
    }.joinToString("") {
        when (it.path) {
            StubMasterOpsServer.CONTRACT_REVISION_APPROVALS -> "E"
            StubMasterOpsServer.APPROVAL_DECISIONS -> "D"
            else -> "C"
        }
    }
    fun bodies(id: String, path: String) = StubMasterOpsServer.requestsForPath(path).filter { f.idOf(it.body) == id }.map { String(it.body) }
    fun due(id: String) = root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
    fun pastDeadline(id: String) = root().use {
        it.update("UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, execute_before = UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE WHERE approval_id = ?", id)
    }
    fun run(id: String) {
        due(id)
        recovery.recoverDue()
    }
    fun fingerprint(id: String) = root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) as String }

    /** 응답 유실(503)로 EXECUTION_UNKNOWN이 된 승인 하나. */
    fun unknownApproval(): Pair<String, String> {
        val (id, m) = f.requested()
        f.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
        f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
        return id to m
    }

    Given("RC-6 B2-3 first MID: the same recovery order on the MID operation") {
        Then("lost response → decision DECIDED (IssueFirstDefaultMerchantAccount, same mop1) → EXECUTED mid_ with no new POST; NONE before the deadline resends to first-default-merchant-accounts") {
            val (id, m) = f.requestedMid()
            f.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            run(id)
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.FIRST_DEFAULT_MERCHANT_ACCOUNTS).count { f.idOf(it.body) == id } shouldBe 2
            f.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = f.midOf(m), operation = "IssueFirstDefaultMerchantAccount"))
            run(id)
            listOf(status(id), execution(id)) shouldBe listOf("EXECUTED|-", "${f.midOf(m)}|-|-|-")
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.FIRST_DEFAULT_MERCHANT_ACCOUNTS).count { f.idOf(it.body) == id } shouldBe 2
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).count { f.idOf(it.body) == id } shouldBe 0
        }
    }

    Given("RC-7 B2-3 review m-1: a foreign success reference (C-34) converges quietly") {
        Then("execute answers ctr…#3 for #2 → flag REFERENCE_MISMATCH; N recovery cycles whose lookup keeps returning it: no POST, one integrity audit, one UNKNOWN audit, one alert, one OWNER_UNRESOLVABLE finding") {
            val (id, m) = f.requested()
            val foreign = f.contractOf(m) + "#3"
            f.executeReplies[id] = f.succeeded(foreign)
            val before = alerts("REFERENCE_MISMATCH")
            f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            f.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = foreign))
            repeat(3) { run(id) }
            listOf(status(id), execution(id).split('|')[2], calls(id)) shouldBe listOf("EXECUTION_UNKNOWN|-", "REFERENCE_MISMATCH", "EDDD")
            listOf(audits(id, "APPROVAL_INTEGRITY_VIOLATION"), audits(id, "APPROVAL_EXECUTION_UNKNOWN")) shouldBe listOf(1L, 1L)
            (alerts("REFERENCE_MISMATCH") - before) shouldBe 1.0
            findings(id) shouldBe listOf("OWNER_UNRESOLVABLE|$m|REFERENCE_MISMATCH")
        }
    }

    Given("RC-1 ① decision lookup first") {
        Then("DECIDED with the same mop1 fingerprint → EXECUTED without any execution POST; the lookup body carries only approvalId + mop.<id>") {
            val (id, m) = unknownApproval()
            f.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = f.contractOf(m) + "#2"))
            run(id)
            listOf(status(id), execution(id), calls(id)) shouldBe listOf("EXECUTED|-", "${f.contractOf(m)}#2|-|-|-", "ED")
            bodies(id, StubMasterOpsServer.APPROVAL_DECISIONS).single() shouldBe """{"approvalId":"$id","correlationId":"mop.$id"}"""
        }
        Then("DECIDED with a different fingerprint → integrity flag first + alert, then applied (flag kept for review — I-35)") {
            val (id, _) = unknownApproval()
            val before = alerts("DECISION_CONTENT_MISMATCH")
            f.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson("mop1:" + "0".repeat(64), rejection = "REVISION_ALREADY_APPROVED"))
            run(id)
            listOf(status(id), execution(id), calls(id)) shouldBe listOf("OWNER_REJECTED|REVISION_ALREADY_APPROVED", "-|REVISION_ALREADY_APPROVED|DECISION_CONTENT_MISMATCH|-", "ED")
            (alerts("DECISION_CONTENT_MISMATCH") - before) shouldBe 1.0
        }
        Then("lookup unavailable (503) → UNKNOWN recorded, nothing else this cycle; NONE before the deadline → the same stored bytes are resent") {
            val (id, _) = unknownApproval()
            f.decisionReplies[id] = Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}")
            run(id)
            listOf(status(id), calls(id)) shouldBe listOf("EXECUTION_UNKNOWN|-", "ED")
            f.decisionReplies.remove(id)
            run(id)
            calls(id) shouldBe "EDDE"
            bodies(id, StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).let { (it.size to it.distinct().size) } shouldBe (2 to 1)
        }
    }

    Given("RC-2 ② past executeBefore + deadline-grace: deadline confirmation only (no execution POST)") {
        Then("NOT_YET_DUE (MASTER clock behind) → next cycle; APPROVAL_EXPIRED → OWNER_REJECTED; the confirmation body = the stored bytes") {
            val (id, _) = unknownApproval()
            pastDeadline(id)
            run(id)
            listOf(status(id), calls(id)) shouldBe listOf("EXECUTION_UNKNOWN|-", "EDC")
            f.deadlineReplies[id] = { Reply(200, StubMasterOpsServer.rejected("APPROVAL_EXPIRED")) }
            run(id)
            listOf(status(id), calls(id)) shouldBe listOf("OWNER_REJECTED|APPROVAL_EXPIRED", "EDCDC")
            bodies(id, StubMasterOpsServer.DEADLINE_CONFIRMATIONS).distinct() shouldBe bodies(id, StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).distinct()
        }
        Then("a replayed success from the deadline confirmation → EXECUTED") {
            val (id, m) = unknownApproval()
            pastDeadline(id)
            f.deadlineReplies[id] = { Reply(200, StubMasterOpsServer.succeeded(f.contractOf(m) + "#2", replayed = true)) }
            run(id)
            listOf(status(id), calls(id)) shouldBe listOf("EXECUTED|-", "EDC")
        }
    }

    Given("RC-3 ③ participant re-check and ⑤ resend") {
        Then("the requester lost OPERATOR → resend halted once (APPROVAL_RESEND_HALTED), no POST; later cycles only look up; past the deadline the confirmation closes it") {
            val operator = f.operator()
            val (id, _) = f.requested(operator)
            f.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            f.approve(id)
            root().use { it.update("UPDATE admin_account SET active_roles = '' WHERE actor_id = ?", operator.id.value) }
            run(id)
            run(id)
            listOf(execution(id).split('|')[3], calls(id), audits(id, "APPROVAL_RESEND_HALTED").toString()) shouldBe listOf("REQUESTER_NOT_ELIGIBLE", "EDD", "1")
            pastDeadline(id)
            f.deadlineReplies[id] = { Reply(200, StubMasterOpsServer.rejected("APPROVAL_EXPIRED")) }
            run(id)
            listOf(status(id), calls(id)) shouldBe listOf("OWNER_REJECTED|APPROVAL_EXPIRED", "EDDDC")
        }
    }

    Given("RC-4 integrity flag convergence (409 — C-28): no execution POST, lookups only, OWNER_UNRESOLVABLE once past the deadline") {
        Then("execute 409 → flag (one audit) → NONE before the deadline: no POST → deadline confirmation 409 → finding once + alert, still EXECUTION_UNKNOWN, no auto-closing") {
            val (id, m) = f.requested()
            f.executeReplies[id] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") }
            f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            run(id)
            calls(id) shouldBe "ED"
            pastDeadline(id)
            f.deadlineReplies[id] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") }
            val before = alerts("OWNER_UNRESOLVABLE")
            run(id)
            run(id)
            listOf(status(id), execution(id), calls(id)) shouldBe listOf("EXECUTION_UNKNOWN|-", "-|-|IDEMPOTENCY_KEY_REUSED|-", "EDDCDC")
            findings(id) shouldBe listOf("OWNER_UNRESOLVABLE|$m|IDEMPOTENCY_KEY_REUSED")
            listOf(audits(id, "APPROVAL_INTEGRITY_VIOLATION"), (alerts("OWNER_UNRESOLVABLE") - before).toLong()) shouldBe listOf(1L, 1L)
        }
        Then("decision lookup on a flagged approval: DECIDED (another fingerprint) is applied and the flag kept") {
            val (id, m) = f.requested()
            f.executeReplies[id] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") }
            f.approve(id)
            f.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson("mop1:" + "1".repeat(64), reference = f.contractOf(m) + "#2"))
            run(id)
            listOf(status(id), execution(id).split('|')[2]) shouldBe listOf("EXECUTED|-", "IDEMPOTENCY_KEY_REUSED")
        }
    }

    Given("RC-5 400 persistent (C-28): halt OWNER_MALFORMED on the first 400, lookups with backoff, OWNER_UNRESOLVABLE after the deadline, fixed POST total") {
        Then("execute 400 and deadline 400: one execution POST in total over N cycles; finding once; never OWNER_REJECTED/EXECUTED; 413 behaves like 400") {
            val (id, m) = f.requested()
            f.executeReplies[id] = { Reply(400, "{\"error\":\"REQUEST_MALFORMED\"}") }
            f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            repeat(3) { run(id) }
            pastDeadline(id)
            f.deadlineReplies[id] = { Reply(400, "{\"error\":\"REQUEST_MALFORMED\"}") }
            repeat(3) { run(id) }
            listOf(status(id), execution(id), calls(id)) shouldBe listOf("EXECUTION_UNKNOWN|-", "-|-|-|OWNER_MALFORMED", "EDDDDCDCDC")
            findings(id) shouldBe listOf("OWNER_UNRESOLVABLE|$m|REQUEST_MALFORMED")
            audits(id, "APPROVAL_RESEND_HALTED") shouldBe 1L
            // 회복 backoff는 가맹점 정책(상한 PT30M) — 다음 시도는 지금부터 30분을 넘지 않는다.
            root().use { (it.scalar("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), next_attempt_at) <= 1800 FROM admin_owner_execution WHERE approval_id = ?", id) as Number).toLong() } shouldBe 1L

            val (big, _) = f.requested()
            f.executeReplies[big] = { Reply(413, "") }
            f.approve(big)
            run(big)
            listOf(execution(big).split('|')[3], calls(big)) shouldBe listOf("OWNER_MALFORMED", "ED")
        }
        Then("401 / 404 → UNKNOWN + alert, not halted — the next cycle resends the same bytes") {
            val (id, _) = f.requested()
            f.executeReplies[id] = { Reply(401, "{\"error\":\"UNAUTHENTICATED\"}") }
            val before = alerts("MASTER_UNAUTHORIZED")
            f.approve(id)
            f.executeReplies[id] = { Reply(404, "{\"error\":\"NOT_FOUND\"}") }
            run(id)
            listOf(status(id), execution(id).split('|')[3], calls(id)) shouldBe listOf("EXECUTION_UNKNOWN|-", "-", "EDE")
            (alerts("MASTER_UNAUTHORIZED") - before) shouldBe 1.0
            root().use { it.scalar("SELECT last_failure FROM admin_owner_execution WHERE approval_id = ?", id) } shouldBe "MASTER_NOT_FOUND"
        }
    }
})

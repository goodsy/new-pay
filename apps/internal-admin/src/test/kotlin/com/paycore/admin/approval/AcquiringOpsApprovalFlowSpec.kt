package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.AcquiringOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.approval.application.provided.RequestReacquisition
import com.paycore.admin.approval.application.OwnerOutcomeApplier
import io.micrometer.core.instrument.MeterRegistry
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubFinancialOpsServer
import com.paycore.admin.support.StubFinancialOpsServer.Reply
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 재매입 2인 승인 ADMIN 절반(ACQ-ADMIN-001 B1-A §3.1·§17.5 — D-ACQA-001..004). 실제 MySQL 8.4, FINANCIAL은 실제 HTTP 대역([StubFinancialOpsServer] — 서명을 앱과 독립으로
 * 조회 키·조작 키 각각 검증, 결정 본문의 내용 hash도 시험 쪽 독립 구현). 명시 Fixture: 원 파일(시험마다 새 매입사 코드)의 SALE 1·3이 기관 거절(세대 0), 원승인 10,000원, 레코드 3은
 * 취소 5,000원을 받아 예상 순액 5,000원. 시간 경과만 root로 앞당긴다(OwnerExecutionRecoverySpec과 같은 방식).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AcquiringOpsApprovalFlowSpec(
    acquiring: AcquiringOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    approvals: ApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    applier: OwnerOutcomeApplier,
    registry: MeterRegistry,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubFinancialOpsServer.start()
    val json = JsonMapper.builder().build()
    val counter = AtomicLong(System.nanoTime() % 100_000_000L)
    fun root() = AdminDbAccounts.root(container)
    fun alerts(code: String) = registry.find("paycore.admin.ops.integrity_alert").tags("code", code).counters().sumOf { it.count() }
    fun account(actorId: String) = root().use { it.column("SELECT CONCAT(COALESCE(last_totp_step, -1), '|', failed_attempts) FROM admin_account WHERE actor_id = ?", actorId).single() }
    fun executionRows(id: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_owner_execution WHERE approval_id = ?", id) as Number).toLong() }
    fun newFile() = "ACQ.F${counter.incrementAndGet()}.TEST.20261105.001"
    fun operator(): AdminAccount = path.withRolesViaApproval(AdminRole.OPERATOR)
    fun approver() = ready.bootstrapped(AdminRole.APPROVER)

    /** 원 파일의 미리 보기: 기본 1·3 eligible(3은 취소 5,000원). */
    fun preview(fileId: String, vararg items: String = arrayOf(StubFinancialOpsServer.targetItemJson(1), StubFinancialOpsServer.targetItemJson(3, cancelled = 5_000))) =
        StubFinancialOpsServer.reply(StubFinancialOpsServer.targetsPath(fileId), Reply(200, StubFinancialOpsServer.targetsJson(fileId, *items)))

    fun request(operator: AdminAccount, fileId: String, seqs: List<Int> = listOf(1, 3), code: String = "INSTITUTION_REQUESTED", evidence: String = "INQ-2026-0001",
                reason: String = "acquirer asked to resend") = acquiring.requestReacquisition(
        RequestReacquisition(operator.id.value, storage.load(operator.id).sessionGeneration, fileId, seqs, code, evidence, reason, path.context()),
    )

    fun requested(operator: AdminAccount = operator(), fileId: String = newFile()): Pair<String, String> {
        preview(fileId)
        val outcome = request(operator, fileId)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture re-acquisition request failed: $outcome")).approvalId to fileId
    }

    fun approve(id: String, who: LoginReadyAccounts.LoginReady = approver()) =
        owners.approve(DecideApproval(id, who.account.id.value, storage.load(who.account.id).sessionGeneration, TestTotp.code(who.secret), path.context()))

    // 실행 경로는 하나(모든 승인 공유) — 승인 ID별 응답을 시험이 정한다.
    val executeReplies = ConcurrentHashMap<String, (ByteArray) -> Reply>()
    StubFinancialOpsServer.replyWith(StubFinancialOpsServer.EXECUTE) { body ->
        val id = json.readTree(body).get("approvalId").asString()
        executeReplies[id]?.invoke(body) ?: Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")
    }
    fun executed(newFileId: String): (ByteArray) -> Reply = { Reply(200, StubFinancialOpsServer.decisionJson(it, "EXECUTED", resultFileId = newFileId)) }

    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun execution(id: String) = root().use {
        it.scalar("SELECT CONCAT(fingerprint, '|', COALESCE(owner_reference, '-'), '|', COALESCE(owner_result_code, '-'), '|', COALESCE(integrity_flag, '-')) FROM admin_owner_execution WHERE approval_id = ?", id) as String?
    }
    fun audits(id: String) = root().use {
        it.column("SELECT CONCAT(action, '|', COALESCE(target_actor_id, '-'), '|', COALESCE(target_ref, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id)
    }
    fun fileAudits(fileId: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_ref = ? ORDER BY event_id", fileId) }
    fun rowsFor(fileId: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", fileId) as Number).toLong() }
    fun posts(id: String) = StubFinancialOpsServer.received.count { it.path == StubFinancialOpsServer.EXECUTE && json.readTree(it.body).get("approvalId").asString() == id }
    fun due(id: String) = root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }

    Given("AQ-1 a re-acquisition request by an OPERATOR (D-ACQA-002·003)") {
        Then("it stores the file-target approval (version 0, ACQFILE key), the INSERT-only command with the pinned records, an audit row; the preview used the READ key") {
            val (id, fileId) = requested()
            root().use {
                it.column(
                    "SELECT CONCAT(r.kind, '|', r.status, '|', r.target_kind, '|', r.target_ref, '|', r.target_security_version, '|', r.unresolved_key, '|', c.original_file_id, " +
                        "'|', c.reason_code, '|', c.evidence_ref, '|', (c.pinned_target = r.target_snapshot), '|', JSON_LENGTH(c.pinned_target, '$.items'), '|', " +
                        "JSON_EXTRACT(c.pinned_target, '$.items[1].expectedNetMinor')) FROM admin_approval_request r JOIN admin_acquiring_ops_command c ON c.approval_id = r.approval_id " +
                        "WHERE r.approval_id = ?",
                    id,
                )
            } shouldBe listOf("ACQ_REACQUISITION|PENDING|ACQUISITION_FILE|$fileId|0|ACQFILE:$fileId|$fileId|INSTITUTION_REQUESTED|INQ-2026-0001|1|2|5000")
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|-|$fileId")
            val call = StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.targetsPath(fileId)).single()
            listOf(call.method, call.signatureValid, call.operationSignatureValid, String(call.body)) shouldBe listOf("POST", true, false, "{\"seqs\":[1,3]}")
        }
        Then("invalid input is 400 before FINANCIAL is called; a non-OPERATOR is denied without a call") {
            val operator = operator()
            val fileId = newFile()
            preview(fileId)
            listOf(
                request(operator, fileId, seqs = listOf(3, 1)), request(operator, fileId, code = "WHIM"), request(operator, fileId, evidence = "has space"),
                request(operator, fileId, reason = "card 4111 1111 1111 1111"), request(storage.securityAdmin(), fileId),
                // B1-A 리뷰 M-1: 형식에 맞는 근거라도 카드 번호 패턴이면 저장 전(TX·FINANCIAL 호출 전)에 거부.
                request(operator, fileId, evidence = "4111111111111111"), request(operator, fileId, evidence = "4111-1111-1111-1111"),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("INVALID_INPUT", "seqs", "ORDER"), ApprovalOutcome.Rejected("INVALID_INPUT", "reasonCode", "UNKNOWN_CODE"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"), ApprovalOutcome.Rejected("INVALID_INPUT", "reason", "CARD_NUMBER_PATTERN"),
                ApprovalOutcome.Denied,
                ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "CARD_NUMBER_PATTERN"), ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "CARD_NUMBER_PATTERN"),
            )
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_acquiring_ops_command WHERE evidence_ref REGEXP '[0-9]{13}'") as Number).toLong() } shouldBe 0L
            (rowsFor(fileId) to StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.targetsPath(fileId)).size) shouldBe (0L to 0)
        }
        Then("advisory (I-8 — FINANCIAL-computed eligibility only): any refused record refuses the whole bundle with an audit row; FINANCIAL down → 503, missing file → 400") {
            val operator = operator()
            val refused = newFile().also { preview(it, StubFinancialOpsServer.targetItemJson(1), StubFinancialOpsServer.targetItemJson(3, status = "UNKNOWN", eligible = false, refusal = "UNKNOWN_NOT_RECONCILED")) }
            val down = newFile().also { StubFinancialOpsServer.reply(StubFinancialOpsServer.targetsPath(it), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}")) }
            val missing = newFile()
            listOf(request(operator, refused), request(operator, down), request(operator, missing)) shouldBe listOf(
                ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Unavailable("FINANCIAL_UNAVAILABLE"), ApprovalOutcome.Rejected("ACQUISITION_FILE_NOT_FOUND"),
            )
            listOf(rowsFor(refused), rowsFor(down), rowsFor(missing)) shouldBe listOf(0L, 0L, 0L)
            fileAudits(refused) shouldBe listOf("APPROVAL_DENIED|kind=ACQ_REACQUISITION;reason=TARGET_NOT_ELIGIBLE_ADVISORY")
            fileAudits(down) shouldBe emptyList()
        }
        Then("a second request for the same original file while one is unresolved is 409 DUPLICATE_UNRESOLVED (ACQFILE 1062), audited, one row") {
            val (_, fileId) = requested()
            preview(fileId)
            request(operator(), fileId) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            rowsFor(fileId) shouldBe 1L
            fileAudits(fileId).last() shouldBe "APPROVAL_DENIED|kind=ACQ_REACQUISITION;reason=DUPLICATE_UNRESOLVED"
        }
    }

    Given("AQ-2 an approval by an APPROVER with step-up TOTP — 2-TX, FINANCIAL operation call outside the DB TX with the OPERATION key") {
        Then("200 EXECUTED → EXECUTED: one POST signed with the operation key only, body content hash = execution fingerprint (independent), new file id as owner reference") {
            val operator = operator()
            val (id, fileId) = requested(operator)
            val newFileId = fileId.removeSuffix("001") + "002"
            executeReplies[id] = executed(newFileId)
            val approver = approver()
            approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            val post = StubFinancialOpsServer.received.single { it.path == StubFinancialOpsServer.EXECUTE && json.readTree(it.body).get("approvalId").asString() == id }
            listOf(post.operationSignatureValid, post.signatureValid) shouldBe listOf(true, false)
            val body = json.readTree(post.body)
            listOf(body.get("originalFileId").asString(), body.get("requestedBy").asString(), body.get("approvedBy").asString(), body.get("items").toString()) shouldBe listOf(
                fileId, operator.id.value, approver.account.id.value,
                """[{"seq":1,"expectedGeneration":0,"expectedStatus":"REJECTED"},{"seq":3,"expectedGeneration":0,"expectedStatus":"REJECTED"}]""",
            )
            execution(id) shouldBe "${StubFinancialOpsServer.contentHashOf(post.body)}|$newFileId|-|-"
            status(id) shouldBe "EXECUTED|-"
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|-|$fileId", "APPROVAL_EXECUTING|-|$fileId", "APPROVAL_EXECUTED|-|$fileId")
            // 승인 A 단계 미리 보기는 조회 키(요청 1 + 승인 1).
            StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.targetsPath(fileId)).map { it.signatureValid } shouldBe listOf(true, true)
        }
        // B1-A 리뷰 m-3(a): 이 시험은 역할 부족(APPROVER 없음 → Denied)이다 — 이름을 결과에 맞췄다. 자기 승인(SELF_APPROVAL)은 다음 시험.
        Then("the requesting OPERATOR without the APPROVER role is denied by role (Denied, before the two-person check) — PENDING, no POST") {
            val operator = operator()
            val (id, _) = requested(operator)
            owners.approve(DecideApproval(id, operator.id.value, storage.load(operator.id).sessionGeneration, "000000", path.context())) shouldBe ApprovalOutcome.Denied
            posts(id) shouldBe 0
            status(id) shouldBe "PENDING|-"
        }
        Then("B1-A review m-3(a): an OPERATOR who also holds APPROVER cannot approve their own re-acquisition (Refused SELF_APPROVAL) — PENDING, TOTP not consumed, no POST, denial audited") {
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val (id, fileId) = requested(storage.load(both.account.id))
            val before = account(both.account.id.value)
            approve(id, both) shouldBe ApprovalOutcome.Refused("SELF_APPROVAL")
            listOf(status(id), posts(id).toString(), executionRows(id).toString(), account(both.account.id.value)) shouldBe listOf("PENDING|-", "0", "0", before)
            audits(id).last() shouldBe "APPROVAL_DENIED|-|$fileId"
        }
        Then("B1-A review m-3(b): a wrong step-up TOTP → STEP_UP_FAILED: TX1 rolled back (PENDING, no execution row, no EXECUTING audit), the failure counted in a separate TX, step not consumed, no POST") {
            val (id, fileId) = requested()
            val who = approver()
            val before = account(who.account.id.value)
            val wrong = TestTotp.code(who.secret).let { if (it == "000000") "111111" else "000000" }
            owners.approve(DecideApproval(id, who.account.id.value, storage.load(who.account.id).sessionGeneration, wrong, path.context())) shouldBe ApprovalOutcome.StepUpFailed
            listOf(status(id), posts(id).toString(), executionRows(id).toString()) shouldBe listOf("PENDING|-", "0", "0")
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|-|$fileId")
            val (stepBefore, failuresBefore) = before.split('|')
            val (stepAfter, failuresAfter) = account(who.account.id.value).split('|')
            listOf(stepAfter, failuresAfter.toInt() - failuresBefore.toInt()) shouldBe listOf<Any>(stepBefore, 1)
            // 같은 승인은 그대로 승인 가능(롤백이 상태를 남기지 않음).
            executeReplies[id] = executed(fileId.removeSuffix("001") + "002")
            approve(id, who) shouldBe ApprovalOutcome.Executed(id)
        }
        Then("a changed target (generation or eligibility) → INVALIDATED(TARGET_CHANGED), no POST; FINANCIAL preview down → 503, still PENDING") {
            val (changed, changedFile) = requested()
            preview(changedFile, StubFinancialOpsServer.targetItemJson(1, generation = 1, status = "AWAITING", eligible = false, refusal = "NOT_LATEST_GENERATION"), StubFinancialOpsServer.targetItemJson(3, cancelled = 5_000))
            approve(changed) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
            val (down, downFile) = requested()
            StubFinancialOpsServer.reply(StubFinancialOpsServer.targetsPath(downFile), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            approve(down) shouldBe ApprovalOutcome.Unavailable("FINANCIAL_UNAVAILABLE")
            listOf(status(changed), status(down), "${posts(changed)}${posts(down)}") shouldBe listOf("INVALIDATED|TARGET_CHANGED", "PENDING|-", "00")
        }
        Then("FINANCIAL REJECTED is 200 (I-16) → OWNER_REJECTED(code); NOT_REQUIRED (D-ACQA-004) → EXECUTED with code NOT_REQUIRED and no file") {
            val (rejected, _) = requested()
            executeReplies[rejected] = { Reply(200, StubFinancialOpsServer.decisionJson(it, "REJECTED", code = "TARGET_CHANGED")) }
            val (notRequired, _) = requested()
            executeReplies[notRequired] = { Reply(200, StubFinancialOpsServer.decisionJson(it, "NOT_REQUIRED")) }
            listOf(approve(rejected), approve(notRequired)) shouldBe listOf(ApprovalOutcome.OwnerRejected("TARGET_CHANGED"), ApprovalOutcome.Executed(notRequired))
            listOf(status(rejected), status(notRequired)) shouldBe listOf("OWNER_REJECTED|TARGET_CHANGED", "EXECUTED|NOT_REQUIRED")
            execution(notRequired)!!.substringAfter('|') shouldBe "-|NOT_REQUIRED|-"
        }
        Then("409 APPROVAL_CONFLICT → EXECUTION_UNKNOWN with the integrity flag (derived, not a decision); a decision whose content hash differs is applied with DECISION_CONTENT_MISMATCH") {
            val (conflict, _) = requested()
            executeReplies[conflict] = { Reply(409, "{\"error\":\"APPROVAL_CONFLICT\"}") }
            val (mismatch, _) = requested()
            executeReplies[mismatch] = { Reply(200, StubFinancialOpsServer.decisionJson(it, "REJECTED", code = "TARGET_CHANGED", contentHash = "acd1:" + "0".repeat(64))) }
            listOf(approve(conflict), approve(mismatch)) shouldBe listOf(
                ApprovalOutcome.Executing(conflict, "EXECUTION_UNKNOWN"), ApprovalOutcome.OwnerRejected("TARGET_CHANGED"),
            )
            execution(conflict)!!.substringAfterLast('|') shouldBe "APPROVAL_CONFLICT"
            execution(mismatch)!!.substringAfterLast('|') shouldBe "DECISION_CONTENT_MISMATCH"
        }
        Then("B1-A review m-3(d): FINANCIAL 400 (format refusal, no decision) → OWNER_REJECTED(code) with one integrity alert; the file is freed") {
            val (id, fileId) = requested()
            executeReplies[id] = { Reply(400, "{\"error\":\"REQUEST_MALFORMED\"}") }
            val before = alerts("REQUEST_MALFORMED")
            approve(id) shouldBe ApprovalOutcome.OwnerRejected("REQUEST_MALFORMED")
            listOf(status(id), execution(id)!!.substringAfter('|')) shouldBe listOf("OWNER_REJECTED|REQUEST_MALFORMED", "-|REQUEST_MALFORMED|-")
            (alerts("REQUEST_MALFORMED") - before) shouldBe 1.0
            audits(id).last() shouldBe "APPROVAL_OWNER_REJECTED|-|$fileId"
            preview(fileId)
            (request(operator(), fileId) is ApprovalOutcome.Created) shouldBe true
        }
    }

    Given("AQ-3 recovery (§3.1 D — response lost → EXECUTION_UNKNOWN → recovery job: decision lookup, deadline confirmation)") {
        Then("FINANCIAL committed but the response was lost (503): the job looks the decision up with the operation key and converges to EXECUTED") {
            val (id, fileId) = requested()
            executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            val sent = StubFinancialOpsServer.received.last { it.path == StubFinancialOpsServer.EXECUTE && json.readTree(it.body).get("approvalId").asString() == id }.body
            val newFileId = fileId.removeSuffix("001") + "002"
            StubFinancialOpsServer.reply(StubFinancialOpsServer.decisionPath(id), Reply(200, StubFinancialOpsServer.decisionJson(sent, "EXECUTED", resultFileId = newFileId)))
            due(id)
            recovery.recoverDue()
            status(id) shouldBe "EXECUTED|-"
            execution(id)!!.split('|')[1] shouldBe newFileId
            StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.decisionPath(id)).map { it.method to it.operationSignatureValid } shouldBe listOf("GET" to true)
            posts(id) shouldBe 1
        }
        Then("no decision and still in time → the job resends the same stored bytes; past the deadline → deadline confirmation (stored body) → OWNER_REJECTED(APPROVAL_EXECUTION_EXPIRED)") {
            val (id, _) = requested()
            executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            approve(id)
            StubFinancialOpsServer.reply(StubFinancialOpsServer.decisionPath(id), Reply(200, "{\"decision\":\"NONE\",\"approvalId\":\"$id\"}"))
            due(id)
            recovery.recoverDue()
            val sent = StubFinancialOpsServer.received.filter { it.path == StubFinancialOpsServer.EXECUTE && json.readTree(it.body).get("approvalId").asString() == id }.map { String(it.body) }
            (sent.size to sent.distinct().size) shouldBe (2 to 1)
            root().use {
                it.update(
                    "UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 DAY, execute_before = UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE WHERE approval_id = ?", id,
                )
            }
            StubFinancialOpsServer.replyWith(StubFinancialOpsServer.deadlinePath(id)) { body ->
                Reply(200, StubFinancialOpsServer.decisionJson(body, "REJECTED", code = "APPROVAL_EXECUTION_EXPIRED", origin = "DEADLINE_CONFIRMATION"))
            }
            due(id)
            recovery.recoverDue()
            status(id) shouldBe "OWNER_REJECTED|APPROVAL_EXECUTION_EXPIRED"
            val confirm = StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.deadlinePath(id)).single()
            (confirm.operationSignatureValid to String(confirm.body)) shouldBe (true to sent.first())
            StubFinancialOpsServer.clearDynamic(StubFinancialOpsServer.deadlinePath(id))
        }
        Then("B1-A review m-4: an execution row whose fingerprint prefix is not the kind's (oct1 on a re-acquisition) is not resent or looked up; the job records an integrity violation") {
            val (id, fileId) = requested()
            executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            root().use { it.update("UPDATE admin_owner_execution SET fingerprint = CONCAT('oct1', SUBSTRING(fingerprint, 5)) WHERE approval_id = ?", id) }
            due(id)
            recovery.recoverDue()
            listOf(status(id), posts(id).toString(), StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.decisionPath(id)).size.toString()) shouldBe
                listOf("EXECUTION_UNKNOWN|-", "1", "0")
            root().use {
                it.column("SELECT CONCAT(action, '|', actor_kind, '|', COALESCE(target_ref, '-'), '|', detail) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id).last()
            } shouldBe "APPROVAL_INTEGRITY_VIOLATION|SYSTEM_JOB|$fileId|column=execution_kind"
        }
        Then("B1-A review m-4: a result whose owner reference is not the kind's grammar (a PAYMENT cancellation id on a re-acquisition) is not applied — rolled back, integrity violation") {
            val (id, fileId) = requested()
            executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            applier.apply(id, OwnerOutcomeApplier.Outcome.Started("pcn_01JC0000000000000000000001"), OwnerOutcomeApplier.Source.Job) shouldBe
                ApprovalOutcome.Executing(id, "EXECUTING")
            listOf(status(id), execution(id)!!.split('|')[1]) shouldBe listOf("EXECUTION_UNKNOWN|-", "-")
            root().use {
                it.column("SELECT CONCAT(action, '|', COALESCE(target_ref, '-'), '|', detail) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id).last()
            } shouldBe "APPROVAL_INTEGRITY_VIOLATION|$fileId|column=owner_reference"
        }
    }

    Given("AQ-4 reject / withdraw go through the shared decision table (audit target_ref = file id)") {
        Then("an APPROVER rejects with TOTP → REJECTED, frees the file; the requester withdraws → WITHDRAWN") {
            val (rejectedId, fileId) = requested()
            val who = approver()
            approvals.reject(DecideApproval(rejectedId, who.account.id.value, storage.load(who.account.id).sessionGeneration, TestTotp.code(who.secret), path.context())) shouldBe
                ApprovalOutcome.Closed("REJECTED")
            audits(rejectedId).last() shouldBe "APPROVAL_REJECTED|-|$fileId"
            val operator = operator()
            preview(fileId)
            val again = (request(operator, fileId) as ApprovalOutcome.Created).approvalId
            approvals.withdraw(
                com.paycore.admin.approval.application.provided.WithdrawApproval(again, operator.id.value, storage.load(operator.id).sessionGeneration, path.context()),
            ) shouldBe ApprovalOutcome.Closed("WITHDRAWN")
            listOf(status(rejectedId), status(again)) shouldBe listOf("REJECTED|-", "WITHDRAWN|-")
        }
    }
})

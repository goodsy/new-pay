package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.JustificationResult
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OperatorCancelJustificationQuery
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.merchantops.application.provided.MerchantPendingApprovals
import com.paycore.admin.merchantops.application.provided.PendingMerchantApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * 가맹점 계약 승인 ADMIN 절반(MERCHANT-OPS-002 B2-2 §8.2 B2-2-9 — §9.5). 실제 MySQL 8.4, MASTER는 실제 HTTP 대역([StubMasterOpsServer] — 서명 독립 검증). 명시 Fixture는
 * [MerchantOpsApprovalFixture]: Revision 2 승인 전, 등록자 [StubMasterOpsServer.REGISTRANT], 근거 참조 `CONTRACT-2026/0001#p1`.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantOpsApprovalServiceSpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    query: ApprovalQuery,
    justifications: OperatorCancelJustificationQuery,
    pending: MerchantPendingApprovals,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    registry: MeterRegistry,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val f = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    f.install()
    afterSpec { f.uninstall() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    fun alerts(code: String) = registry.find("paycore.admin.ops.integrity_alert").tags("code", code).counters().sumOf { it.count() }
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun execution(id: String) = root().use {
        it.scalar(
            "SELECT CONCAT(COALESCE(owner_reference, '-'), '|', COALESCE(owner_result_code, '-'), '|', COALESCE(integrity_flag, '-'), '|', COALESCE(resend_halted_reason, '-'), '|', " +
                "COALESCE(last_failure, '-')) FROM admin_owner_execution WHERE approval_id = ?",
            id,
        ) as String?
    }
    fun audits(id: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) }
    // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.8 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 행은 내부 키(target_merchant_key·COALESCE(target_merchant_id, target_ref))로 찾는다
    fun merchantAudits(m: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_merchant_key = ? ORDER BY event_id", m) }
    // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.8 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 행은 내부 키(target_merchant_key·COALESCE(target_merchant_id, target_ref))로 찾는다
    fun rowsFor(m: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE COALESCE(target_merchant_id, target_ref) = ?", m) as Number).toLong() }
    fun posts(id: String) = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).filter { f.idOf(it.body) == id }
    fun detailCalls(m: String) = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).size
    fun account(actorId: String) = root().use { it.column("SELECT CONCAT(COALESCE(last_totp_step, -1), '|', failed_attempts) FROM admin_account WHERE actor_id = ?", actorId).single() }
    /** 별도 TX의 STEP_UP 실패 기록(rollback되지 않음)이 남기는 인증 실패 감사 행 수 — 등록자 검사가 TOTP 앞인지 보는 증거(B3-1F review MAJOR-1). */
    fun authFailures(actorId: String) = root().use {
        (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE actor_id = ? AND action = 'AUTHENTICATION_FAILED'", actorId) as Number).toLong()
    }

    Given("MR-1 an OPERATOR requests a contract approval (D-MOPS-008 ① evidence required, C-20 one unresolved per merchant)") {
        Then("it stores the MERCHANT-target approval (version 0, MERCHANT: key), the INSERT-only command with pinned registeredBy and the evidence, one audit row without the evidence") {
            val (id, m) = f.requested()
            root().use {
                it.column(
                    "SELECT CONCAT(r.kind, '|', r.status, '|', r.target_kind, '|', r.target_ref, '|', r.target_security_version, '|', r.unresolved_key, '|', c.command_kind, '|', " +
                        "c.evidence_ref, '|', (c.pinned_target = r.target_snapshot), '|', JSON_UNQUOTE(JSON_EXTRACT(c.pinned_target, '$.registeredBy')), '|', " +
                        "JSON_EXTRACT(c.pinned_target, '$.revisionNo'), '|', JSON_EXTRACT(c.pinned_target, '$.approved')) " +
                        "FROM admin_approval_request r JOIN admin_merchant_ops_command c ON c.approval_id = r.approval_id WHERE r.approval_id = ?",
                    id,
                )
            } shouldBe listOf(
                // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
                "MERCHANT_CONTRACT_APPROVAL|PENDING|MERCHANT|${MerchantOpsApprovalFixture.DEFAULT_CODE}|0|MERCHANT:$m|CONTRACT_APPROVAL|${MerchantOpsApprovalFixture.EVIDENCE}|1|${StubMasterOpsServer.REGISTRANT}|2|false",
            )
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|kind=MERCHANT_CONTRACT_APPROVAL")
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE detail LIKE '%CONTRACT-2026%' OR correlation_id LIKE '%CONTRACT-2026%'") as Number).toLong() } shouldBe 0L
            // 상세 조회 서명 = 조회 키(대역이 독립 검증).
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).map { it.method to it.signatureValid } shouldBe listOf("GET" to true)
        }
        Then("invalid input is 400 before MASTER is called (evidence required — empty, spaced, 130 chars; revision range); a non-OPERATOR is denied without a call") {
            val operator = f.operator()
            val m = f.newMerchantId().also { f.detail(it) }
            listOf(
                f.request(operator, m, evidence = ""), f.request(operator, m, evidence = " CONTRACT-1"), f.request(operator, m, evidence = "x".repeat(130)),
                f.request(operator, m, revisionNo = 0), f.request(operator, m, revisionNo = 2_147_483_648), f.request(operator, m, contractId = "ctr_x"),
                f.request(storage.load(f.approver().account.id), m),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"), ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"), ApprovalOutcome.Rejected("INVALID_INPUT", "revisionNo", "RANGE"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "revisionNo", "RANGE"), ApprovalOutcome.Rejected("INVALID_INPUT", "contractId", "FORMAT"), ApprovalOutcome.Denied,
            )
            (rowsFor(m) to detailCalls(m)) shouldBe (0L to 0)
        }
        Then("advisory (MASTER detail): an approved, ended or missing revision is 409 TARGET_NOT_ELIGIBLE_ADVISORY with an audit; unknown merchant 404; MASTER down 503") {
            val operator = f.operator()
            val approved = f.newMerchantId().also { f.detail(it, approvedAt = "2026-11-10T00:00:00.000Z") }
            val ended = f.newMerchantId().also { f.detail(it, effectiveFrom = "2026-10-01T15:00:00.000Z", effectiveTo = "2026-11-01T15:00:00.000Z") }
            val missing = f.newMerchantId().also { f.detail(it) }
            val unknown = f.newMerchantId()
            val down = f.newMerchantId().also { StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(it), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}")) }
            listOf(f.request(operator, approved), f.request(operator, ended), f.request(operator, missing, revisionNo = 3), f.request(operator, unknown), f.request(operator, down)) shouldBe
                listOf(
                    ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"),
                    ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("MERCHANT_NOT_FOUND"), ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE"),
                )
            listOf(approved, ended, missing, unknown, down).map(::rowsFor) shouldBe listOf(0L, 0L, 0L, 0L, 0L)
            listOf(merchantAudits(approved), merchantAudits(ended), merchantAudits(missing), merchantAudits(down)) shouldBe listOf(
                listOf("APPROVAL_DENIED|kind=MERCHANT_CONTRACT_APPROVAL;reason=REVISION_ALREADY_APPROVED"),
                listOf("APPROVAL_DENIED|kind=MERCHANT_CONTRACT_APPROVAL;reason=REVISION_ENDED"),
                listOf("APPROVAL_DENIED|kind=MERCHANT_CONTRACT_APPROVAL;reason=REVISION_NOT_FOUND"), emptyList(),
            )
        }
        Then("Concurrency Control (real MySQL, two connections): two OPERATORs request the same merchant at once → exactly one approval row; the other is DUPLICATE_UNRESOLVED or RETRY_LATER") {
            val m = f.newMerchantId().also { f.detail(it) }
            val operators = listOf(f.operator(), f.operator())
            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val futures = operators.map { op -> pool.submit<ApprovalOutcome> { start.await(); f.request(op, m) } }
                start.countDown()
                val outcomes = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
                outcomes.count { it is ApprovalOutcome.Created } shouldBe 1
                outcomes.filterNot { it is ApprovalOutcome.Created }.single().let {
                    (it == ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED") || it == ApprovalOutcome.RetryLater) shouldBe true
                }
            } finally {
                pool.shutdownNow()
            }
            rowsFor(m) shouldBe 1L
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_merchant_ops_command WHERE merchant_id = ?", m) as Number).toLong() } shouldBe 1L
        }
        Then("a second request for the same merchant while one is unresolved is 409 DUPLICATE_UNRESOLVED (MERCHANT: 1062), audited, one row") {
            val (_, m) = f.requested()
            f.request(f.operator(), m) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            rowsFor(m) shouldBe 1L
            merchantAudits(m).last() shouldBe "APPROVAL_DENIED|kind=MERCHANT_CONTRACT_APPROVAL;reason=DUPLICATE_UNRESOLVED"
        }
    }

    Given("MR-2 approval by an APPROVER with step-up TOTP — TX1 → MASTER POST outside the DB TX → TX2") {
        Then("200 SUCCEEDED → EXECUTED: one POST of the stored bytes (valid signature), body = execution row, actor-free, key/correlation from the approval id, mop1 = execution fingerprint") {
            val operator = f.operator()
            val (id, m) = f.requested(operator)
            val reference = f.contractOf(m) + "#2"
            f.executeReplies[id] = f.succeeded(reference)
            val approver = f.approver()
            f.approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            val post = posts(id).single()
            post.signatureValid shouldBe true
            val stored = root().use { c ->
                c.prepareStatement("SELECT request_body, fingerprint FROM admin_owner_execution WHERE approval_id = ?").use { s ->
                    s.setString(1, id)
                    s.executeQuery().use { r -> r.next(); r.getBytes(1) to r.getString(2) }
                }
            }
            post.body.contentEquals(stored.first) shouldBe true
            val body = json.readTree(post.body)
            body.propertyNames().toList() shouldBe listOf(
                "idempotencyKey", "correlationId", "approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "evidenceRef", "approvalFingerprint", "contractId", "revisionNo",
            )
            listOf(
                body.get("idempotencyKey").asString(), body.get("correlationId").asString(), body.get("requestedBy").asString(), body.get("approvedBy").asString(),
                body.get("evidenceRef").asString(), body.get("approvalFingerprint").asString(), body.get("contractId").asString(), body.get("revisionNo").asString(),
            ) shouldBe listOf(
                "mop1.$id", "mop.$id", "admin:${operator.id.value}", "admin:${approver.account.id.value}", MerchantOpsApprovalFixture.EVIDENCE, stored.second, f.contractOf(m), "2",
            )
            java.time.Duration.between(java.time.Instant.parse(body.get("approvedAt").asString()), java.time.Instant.parse(body.get("executeBefore").asString())) shouldBe
                java.time.Duration.ofHours(1)
            listOf(status(id), execution(id)) shouldBe listOf("EXECUTED|-", "$reference|-|-|-|-")
            audits(id).map { it.substringBefore('|') } shouldBe listOf("APPROVAL_REQUESTED", "APPROVAL_EXECUTING", "APPROVAL_EXECUTED")
            // 감사·실행 행 어디에도 MASTER 멱등 키 원문 없음(approvalId는 있음).
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE detail LIKE ? OR correlation_id LIKE ?", "%mop1.$id%", "%mop1.$id%") as Number).toLong() } shouldBe 0L
            // A 단계 재조회는 조회 키(요청 1 + 승인 1).
            detailCalls(m) shouldBe 2
        }
        Then("D-MOPS-008 ③: the revision registrant (OPERATOR + APPROVER) cannot approve — 403 APPROVER_IS_REGISTRANT before the TOTP, PENDING, no POST, APPROVAL_DENIED audit") {
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val m = f.newMerchantId()
            f.detail(m, registeredBy = "admin:${both.account.id.value}")
            val id = (f.request(f.operator(), m) as ApprovalOutcome.Created).approvalId
            val before = account(both.account.id.value)
            f.approve(id, both) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            listOf(status(id), posts(id).size.toString(), execution(id) ?: "none", account(both.account.id.value)) shouldBe listOf("PENDING|-", "0", "none", before)
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
            // 다른 APPROVER는 같은 승인을 처리할 수 있다.
            f.executeReplies[id] = f.succeeded(f.contractOf(m) + "#2")
            f.approve(id) shouldBe ApprovalOutcome.Executed(id)
        }
        Then("D-MOPS-008 ③ order (B3-1F review MAJOR-1): the revision registrant with a WRONG TOTP is still refused APPROVER_IS_REGISTRANT — not STEP_UP_FAILED; no failure counted, no AUTHENTICATION_FAILED audit") {
            // 위 시험의 "TOTP 불변"은 TX1 rollback만으로도 성립한다(순서와 무관). 틀린 코드는 순서를 드러낸다: TOTP가 먼저면 STEP_UP_FAILED + 별도 TX 실패 계수·감사.
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val actor = both.account.id.value
            val m = f.newMerchantId()
            f.detail(m, registeredBy = "admin:$actor")
            val id = (f.request(f.operator(), m) as ApprovalOutcome.Created).approvalId
            val before = account(actor)
            val failuresBefore = authFailures(actor)
            f.approve(id, both, totp = TestTotp.wrongCode(both.secret)) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            listOf(status(id), posts(id).size.toString(), execution(id) ?: "none", account(actor), authFailures(actor).toString()) shouldBe
                listOf("PENDING|-", "0", "none", before, failuresBefore.toString())
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
        }
        Then("the requester holding APPROVER is refused SELF_APPROVAL (existing rule); a VIEWER/OPERATOR is denied") {
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val (id, _) = f.requested(storage.load(both.account.id))
            f.approve(id, both) shouldBe ApprovalOutcome.Refused("SELF_APPROVAL")
            val operator = f.operator()
            owners.approve(DecideApproval(id, operator.id.value, storage.load(operator.id).sessionGeneration, "000000", path.context())) shouldBe ApprovalOutcome.Denied
            listOf(status(id), posts(id).size.toString()) shouldBe listOf("PENDING|-", "0")
        }
        Then("pinned ≠ current (registrant changed, revision approved meanwhile or gone) → INVALIDATED(TARGET_CHANGED), no POST; MASTER down → 503, PENDING, TOTP not consumed") {
            val (changed, m1) = f.requested()
            f.detail(m1, registeredBy = "admin:adm_01JCOT0000000000000000OT01")
            val (approvedMeanwhile, m2) = f.requested()
            f.detail(m2, approvedAt = "2026-11-10T00:00:00.000Z")
            val (gone, m3) = f.requested()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m3), Reply(404, "{\"error\":\"MERCHANT_NOT_FOUND\"}"))
            listOf(f.approve(changed), f.approve(approvedMeanwhile), f.approve(gone)) shouldBe List(3) { ApprovalOutcome.Invalidated("TARGET_CHANGED") }
            val (down, m4) = f.requested()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m4), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            val who = f.approver()
            val before = account(who.account.id.value)
            f.approve(down, who) shouldBe ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE")
            listOf(status(changed), status(down), account(who.account.id.value), (posts(changed).size + posts(approvedMeanwhile).size + posts(gone).size + posts(down).size).toString()) shouldBe
                listOf("INVALIDATED|TARGET_CHANGED", "PENDING|-", before, "0")
        }
        Then("TX2 mapping (§9.5 table): REJECTED → OWNER_REJECTED(code); 409 → EXECUTION_UNKNOWN + integrity flag + one APPROVAL_INTEGRITY_VIOLATION; 503 → UNKNOWN; 400 → UNKNOWN + resend halted OWNER_MALFORMED") {
            val (rejected, _) = f.requested().also { f.executeReplies[it.first] = { Reply(200, StubMasterOpsServer.rejected("REVISION_ALREADY_APPROVED")) } }
            val (conflict, _) = f.requested().also { f.executeReplies[it.first] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") } }
            val (busy, _) = f.requested().also { f.executeReplies[it.first] = { Reply(503, "{\"error\":\"OUTCOME_UNKNOWN\"}") } }
            val (malformed, _) = f.requested().also { f.executeReplies[it.first] = { Reply(400, "{\"error\":\"REQUEST_MALFORMED\"}") } }
            val keyAlerts = alerts("IDEMPOTENCY_KEY_REUSED")
            val malformedAlerts = alerts("REQUEST_MALFORMED")
            listOf(f.approve(rejected), f.approve(conflict), f.approve(busy), f.approve(malformed)) shouldBe listOf(
                ApprovalOutcome.OwnerRejected("REVISION_ALREADY_APPROVED"), ApprovalOutcome.Executing(conflict, "EXECUTION_UNKNOWN"),
                ApprovalOutcome.Executing(busy, "EXECUTION_UNKNOWN"), ApprovalOutcome.Executing(malformed, "EXECUTION_UNKNOWN"),
            )
            listOf(status(rejected), status(conflict), status(busy), status(malformed)) shouldBe
                listOf("OWNER_REJECTED|REVISION_ALREADY_APPROVED", "EXECUTION_UNKNOWN|-", "EXECUTION_UNKNOWN|-", "EXECUTION_UNKNOWN|-")
            listOf(execution(conflict), execution(busy), execution(malformed)) shouldBe listOf(
                "-|-|IDEMPOTENCY_KEY_REUSED|-|IDEMPOTENCY_KEY_REUSED", "-|-|-|-|OUTCOME_UNKNOWN", "-|-|-|OWNER_MALFORMED|REQUEST_MALFORMED",
            )
            audits(conflict).count { it.startsWith("APPROVAL_INTEGRITY_VIOLATION") } shouldBe 1
            audits(malformed).count { it.startsWith("APPROVAL_RESEND_HALTED") } shouldBe 1
            listOf(alerts("IDEMPOTENCY_KEY_REUSED") - keyAlerts, alerts("REQUEST_MALFORMED") - malformedAlerts) shouldBe listOf(1.0, 1.0)
        }
    }

    Given("MR-4 B2-3 first Default MID issuance (§9.5 — OPERATOR request, APPROVER + TOTP, MASTER first-default-merchant-accounts)") {
        Then("request: MERCHANT target, same MERCHANT: unresolved key, command FIRST_MID_ISSUANCE with the pinned CSA·revision·PRIMARY Site and the evidence; one audit without the evidence") {
            val (id, m) = f.requestedMid()
            root().use {
                it.column(
                    "SELECT CONCAT(r.kind, '|', r.unresolved_key, '|', c.command_kind, '|', c.evidence_ref, '|', (c.pinned_target = r.target_snapshot), '|', " +
                        "JSON_UNQUOTE(JSON_EXTRACT(c.pinned_target, '$.agreementId')), '|', JSON_UNQUOTE(JSON_EXTRACT(c.pinned_target, '$.primarySiteId')), '|', " +
                        "JSON_EXTRACT(c.pinned_target, '$.accountCount'), '|', JSON_UNQUOTE(JSON_EXTRACT(c.pinned_target, '$.channelType'))) " +
                        "FROM admin_approval_request r JOIN admin_merchant_ops_command c ON c.approval_id = r.approval_id WHERE r.approval_id = ?",
                    id,
                )
            } shouldBe listOf("MERCHANT_FIRST_MID_ISSUANCE|MERCHANT:$m|FIRST_MID_ISSUANCE|${MerchantOpsApprovalFixture.MID_EVIDENCE}|1|${f.agreementOf(m)}|${f.siteOf(m)}|0|WEB")
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|kind=MERCHANT_FIRST_MID_ISSUANCE")
        }
        Then("advisory (MASTER detail): MID exists, revision not approved, unknown CSA or site → 409 TARGET_NOT_ELIGIBLE_ADVISORY (audited); a pending contract approval blocks it (C-20 shared key)") {
            val operator = f.operator()
            val hasMid = f.newMerchantId().also { f.firstMidDetail(it, withAccount = true) }
            val unapproved = f.newMerchantId().also { f.firstMidDetail(it, approvedAt = null) }
            val badCsa = f.newMerchantId().also { f.firstMidDetail(it) }
            val badSite = f.newMerchantId().also { f.firstMidDetail(it) }
            listOf(
                f.requestMid(operator, hasMid), f.requestMid(operator, unapproved), f.requestMid(operator, badCsa, agreementId = "csa_01JCZZ00000000000000000ZZ1"),
                f.requestMid(operator, badSite, siteId = "site_01JCZZ00000000000000000ZZ1"), f.requestMid(operator, badSite, evidence = "free text"),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"),
                ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"),
            )
            listOf(hasMid, unapproved, badCsa, badSite).map(::rowsFor) shouldBe listOf(0L, 0L, 0L, 0L)
            merchantAudits(hasMid) shouldBe listOf("APPROVAL_DENIED|kind=MERCHANT_FIRST_MID_ISSUANCE;reason=MERCHANT_ACCOUNT_EXISTS")
            val (_, m) = f.requested()
            f.firstMidDetail(m)
            f.requestMid(operator, m) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
        }
        Then("approve: stored bytes to first-default-merchant-accounts (14 fields, IssueFirstDefault mop1) → EXECUTED mid_…; the revision registrant may approve the MID (rule is contract-only)") {
            val operator = f.operator()
            val registrant = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.APPROVER) }
            val m = f.newMerchantId()
            f.firstMidDetail(m, registeredBy = "admin:${registrant.account.id.value}")
            val id = (f.requestMid(operator, m) as ApprovalOutcome.Created).approvalId
            f.executeReplies[id] = f.succeeded(f.midOf(m))
            f.approve(id, registrant) shouldBe ApprovalOutcome.Executed(id)
            val post = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.FIRST_DEFAULT_MERCHANT_ACCOUNTS).single { f.idOf(it.body) == id }
            val body = json.readTree(post.body)
            body.propertyNames().toList().takeLast(5) shouldBe listOf("merchantId", "contractId", "agreementId", "revisionNo", "primarySiteId")
            listOf(body.get("merchantId").asString(), body.get("agreementId").asString(), body.get("primarySiteId").asString(), body.get("revisionNo").asString()) shouldBe
                listOf(m, f.agreementOf(m), f.siteOf(m), "1")
            listOf(status(id), execution(id)) shouldBe listOf("EXECUTED|-", "${f.midOf(m)}|-|-|-|-")
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).count { f.idOf(it.body) == id } shouldBe 0
        }
        Then("pinned ≠ current (a MID appeared meanwhile) → INVALIDATED(TARGET_CHANGED), no POST") {
            val (id, m) = f.requestedMid()
            f.firstMidDetail(m, withAccount = true)
            f.approve(id) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.FIRST_DEFAULT_MERCHANT_ACCOUNTS).count { f.idOf(it.body) == id } shouldBe 0
        }
    }

    Given("MR-5 review n-2: a success reference must be this approval's own target") {
        Then("contract approval answered with another revision (ctr…#3 for #2) → EXECUTION_UNKNOWN with integrity flag REFERENCE_MISMATCH and an alert (not EXECUTED)") {
            val (id, m) = f.requested()
            f.executeReplies[id] = f.succeeded(f.contractOf(m) + "#3")
            val before = alerts("REFERENCE_MISMATCH")
            f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            listOf(status(id), execution(id)!!.split('|')[2]) shouldBe listOf("EXECUTION_UNKNOWN|-", "REFERENCE_MISMATCH")
            (alerts("REFERENCE_MISMATCH") - before) shouldBe 1.0
        }
    }

    Given("MR-3 views: the evidence reference is a separate authorized lookup; detail/list carry the pinned target (with registeredBy) but never the evidence; pending approvals") {
        Then("the requester and an APPROVER read {approvalId, evidenceRef} with one audit each; another OPERATOR and a VIEWER are refused; the detail has no evidence") {
            val requester = f.operator()
            val (id, m) = f.requested(requester)
            val approver = f.approver()
            val other = f.operator()
            val viewer = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.VIEWER) }.account
            listOf(requester.id.value, approver.account.id.value, other.id.value, viewer.id.value).map { justifications.view(it, id, path.context()) }.map {
                when (it) {
                    is JustificationResult.EvidenceFound -> "OK:${it.evidenceRef}"
                    else -> it.javaClass.simpleName
                }
            } shouldBe listOf("OK:${MerchantOpsApprovalFixture.EVIDENCE}", "OK:${MerchantOpsApprovalFixture.EVIDENCE}", "Forbidden", "Forbidden")
            root().use {
                it.column("SELECT CONCAT(result_code, '|', target_ref) FROM admin_audit_event WHERE approval_id = ? AND action = 'APPROVAL_JUSTIFICATION_VIEWED' ORDER BY event_id", id)
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            } shouldBe listOf("OK|${MerchantOpsApprovalFixture.DEFAULT_CODE}", "OK|${MerchantOpsApprovalFixture.DEFAULT_CODE}", "FORBIDDEN|${MerchantOpsApprovalFixture.DEFAULT_CODE}", "FORBIDDEN|${MerchantOpsApprovalFixture.DEFAULT_CODE}")
            val detail = query.detail(requester.id.value, id)!!
            json.readTree(detail.merchant!!.pinnedTarget).get("registeredBy").asString() shouldBe StubMasterOpsServer.REGISTRANT
            detail.toString() shouldNotContain MerchantOpsApprovalFixture.EVIDENCE
            query.list(requester.id.value, pendingOnly = true)!!.joinToString().let { it shouldNotContain MerchantOpsApprovalFixture.EVIDENCE }
        }
        Then("C-24 pendingApprovals: the merchant's unresolved approval for view roles; empty for an account without a view role; empty once it ends") {
            val (id, m) = f.requested()
            val noRole = ready.withoutRoles().account
            pending.pendingFor(f.operator().id.value, m) shouldBe listOf(PendingMerchantApproval(id, "MERCHANT_CONTRACT_APPROVAL", "PENDING", revisionNo = 2))
            pending.pendingFor(noRole.id.value, m) shouldBe emptyList()
            f.executeReplies[id] = { Reply(200, StubMasterOpsServer.rejected("REVISION_ENDED")) }
            f.approve(id)
            pending.pendingFor(f.operator().id.value, m) shouldBe emptyList()
        }
    }
})

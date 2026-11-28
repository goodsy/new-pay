package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.BusinessChangeApprovalFixture.Companion.CANARY_PERSON
import com.paycore.admin.approval.BusinessChangeApprovalFixture.Companion.DIGEST
import com.paycore.admin.approval.BusinessChangeApprovalFixture.Companion.EVIDENCE
import com.paycore.admin.approval.BusinessChangeApprovalFixture.Companion.EXECUTE
import com.paycore.admin.approval.BusinessChangeApprovalFixture.Companion.PROPOSALS
import com.paycore.admin.approval.application.BusinessChangeApprovalService
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalUseCase
import com.paycore.admin.approval.application.provided.BusinessChangeOriginalResult
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.approval.application.provided.WithdrawApproval
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequestOutcome
import com.paycore.admin.merchantops.application.provided.BusinessChangeRequests
import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessPendingApprovals
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.TestTotp
import com.paycore.admin.support.column
import com.paycore.admin.support.errorCodeOf
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * BIZ-CRIT-APPROVAL-001 S3 ADMIN 서비스(명세 §3.1·§3.3·§4.1·§7.2 BusinessChangeApprovalServiceSpec — 실제 MySQL 8.4, MASTER는 실제 HTTP 대역). **가맹점 서비스와 같은 순서 표**(m-7):
 * 요청 ① 입력 규칙 ② 세션 역할 ③ 사전 미해결 검사(MASTER 미호출) ④ MASTER 제안(TX 밖) + 응답 요청자 대조(NIT-5) ⑤ 요청 TX(승인 행 → 요청자 잠금 → 명령 행 → 감사) /
 * 승인 ① 세션 역할 ② A(TX 밖, 장애 503·TOTP 불변) ③ TX1 판정표 ④ 참여자 ⑤ 확인 체크(TOTP 전) ⑥ TOTP ⑦ 요청자 재검사 ⑧ 대상 비교(INVALIDATED·MASTER 미호출) ⑨ EXECUTING ⑩ C ⑪ TX2.
 * 회복(결정 조회 → EXECUTED, PAYMENT client 미도달), 원문 프록시(APPROVER·요청자만, 미해결만, 열람 감사), 대기 SPI(OPERATOR·APPROVER만). 기대값은 명세 리터럴.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class BusinessChangeApprovalServiceSpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    requests: BusinessChangeRequests,
    pending: BusinessPendingApprovals,
    service: BusinessChangeApprovalService,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    approvals: ApprovalUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = BusinessChangeApprovalFixture(base, requests, owners, path, storage)
    f.install()
    afterSpec { f.uninstall() }
    afterEach { f.resetProposal() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun audits(id: String) = root().use { it.column("SELECT CONCAT(action, '|', result_code, '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) }
    fun businessAudits(b: String) = root().use { it.column("SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_ref = ? ORDER BY event_id", b) }
    fun proposals(b: String) = StubMasterOpsServer.requestsForPath(PROPOSALS).count { json.readTree(it.body).get("businessId").asString() == b }
    fun executes(id: String) = StubMasterOpsServer.requestsForPath(EXECUTE).count { base.idOf(it.body) == id }
    fun run(id: String) {
        root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
        recovery.recoverDue()
    }
    fun pastDeadline(id: String) = root().use {
        it.update("UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, execute_before = UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE WHERE approval_id = ?", id)
    }
    fun findings(id: String) = root().use { it.column("SELECT CONCAT(kind, '|', COALESCE(payment_ref, '-'), '|', COALESCE(detail_code, '-')) FROM admin_ops_reconciliation_finding WHERE approval_id = ?", id) }
    fun account(actorId: String) = root().use { it.column("SELECT CONCAT(COALESCE(last_totp_step, -1), '|', failed_attempts) FROM admin_account WHERE actor_id = ?", actorId).single() }
    fun authFailures(actorId: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE actor_id = ? AND action = 'AUTHENTICATION_FAILED'", actorId) as Number).toLong() }
    fun rows(b: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", b) as Number).toLong() }
    fun commandRows(b: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_business_change_command WHERE business_id = ?", b) as Number).toLong() }
    fun originalReads(b: String) = StubMasterOpsServer.requestsForPath(BusinessChangeApprovalFixture.originalPath(b)).size

    Given("request — the same step order as the merchant approval service") {
        Then("⑤ success: BUSINESS_CRITICAL_CHANGE / BUSINESS / biz_ / key BUSINESS:<biz_>; command row has ids·digest·field names only; one value-free audit; MASTER body carries evidenceRef and the session actor") {
            val operator = base.operator()
            val (id, b) = f.requested(operator)
            root().use {
                it.scalar(
                    "SELECT CONCAT(r.kind, '|', r.status, '|', r.target_kind, '|', r.target_ref, '|', r.unresolved_key, '|', c.base_version, '|', c.proposal_id, '|', c.content_digest, '|', " +
                        "c.changed_fields, '|', c.representative_changed, '|', c.subject_correction, '|', c.evidence_ref) " +
                        "FROM admin_approval_request r JOIN admin_business_change_command c ON c.approval_id = r.approval_id WHERE r.approval_id = ?", id,
                )
            } shouldBe "BUSINESS_CRITICAL_CHANGE|PENDING|BUSINESS|$b|BUSINESS:$b|0|${f.proposalIdOf(b)}|$DIGEST|displayName|0|0|$EVIDENCE"
            root().use { it.column("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = 'paycore_admin' AND TABLE_NAME = 'admin_business_change_command' ORDER BY ORDINAL_POSITION") } shouldBe
                listOf("approval_id", "business_id", "base_version", "proposal_id", "content_digest", "changed_fields", "representative_changed", "subject_correction", "evidence_ref", "created_at")
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|SUCCESS|$b|kind=BUSINESS_CRITICAL_CHANGE;fields=displayName;rep=N;subject=N;evidence=PRESENT")
            val sent = json.readTree(StubMasterOpsServer.requestsForPath(PROPOSALS).last { json.readTree(it.body).get("businessId").asString() == b }.body)
            listOf(sent.get("evidenceRef").asString(), sent.get("actor").asString(), sent.get("reason").asString(), sent.has("registrationNumber")) shouldBe
                listOf(EVIDENCE, "admin:" + operator.id.value, BusinessChangeApprovalFixture.REASON, false)
            // 승인 행 표시 스냅샷·감사에 상호·대표자 원문이 없다(M-5).
            root().use { it.scalar("SELECT target_snapshot FROM admin_approval_request WHERE approval_id = ?", id) as String }.let { s ->
                listOf(BusinessChangeApprovalFixture.NEW_NAME, CANARY_PERSON).filter { s.contains(it) } shouldBe emptyList()
            }
        }

        Then("① input rules before MASTER: reason missing, evidence missing / malformed / card-number shaped → 400; nothing sent, no audit") {
            val operator = base.operator()
            val b = f.newBusinessId().also { f.state(it) }
            listOf(f.request(operator, b, reason = null), f.request(operator, b, evidence = null), f.request(operator, b, evidence = "bad ref"), f.request(operator, b, evidence = "4111111111111111")) shouldBe
                listOf(
                    BusinessChangeRequestOutcome.Invalid(listOf(BusinessFieldError("reason", "REQUIRED"))),
                    BusinessChangeRequestOutcome.Invalid(listOf(BusinessFieldError("evidenceRef", "REQUIRED"))),
                    BusinessChangeRequestOutcome.Invalid(listOf(BusinessFieldError("evidenceRef", "FORMAT"))),
                    BusinessChangeRequestOutcome.Invalid(listOf(BusinessFieldError("evidenceRef", "CARD_NUMBER_PATTERN"))),
                )
            listOf(proposals(b), businessAudits(b).size) shouldBe listOf(0, 0)
        }

        Then("② only OPERATOR (VIEWER·APPROVER·SECURITY_ADMIN denied) — MASTER not called") {
            val b = f.newBusinessId().also { f.state(it) }
            listOf(AdminRole.VIEWER, AdminRole.APPROVER, AdminRole.SECURITY_ADMIN).map { role -> f.request(path.withRolesViaApproval(role), b) }.toSet() shouldBe
                setOf(BusinessChangeRequestOutcome.Denied)
            proposals(b) shouldBe 0
        }

        Then("③ an unresolved request already exists → 409 DUPLICATE_UNRESOLVED before MASTER (one DENIED audit); ④ MASTER stored rejection → 409 code; MASTER 503 → unavailable") {
            val (_, b) = f.requested()
            val before = proposals(b)
            f.request(base.operator(), b) shouldBe BusinessChangeRequestOutcome.Conflict("DUPLICATE_UNRESOLVED")
            proposals(b) shouldBe before
            businessAudits(b).last() shouldBe "APPROVAL_DENIED|DENIED|kind=BUSINESS_CRITICAL_CHANGE;reason=DUPLICATE_UNRESOLVED"
            val other = f.newBusinessId().also { f.state(it) }
            f.proposalReplies[other] = { Reply(200, StubMasterOpsServer.rejected("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED")) }
            f.request(base.operator(), other) shouldBe BusinessChangeRequestOutcome.Conflict("BUSINESS_CHANGE_APPROVAL_NOT_REQUIRED")
            f.proposalReplies[other] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            f.request(base.operator(), other) shouldBe BusinessChangeRequestOutcome.Unavailable("MASTER_RETRY_SAME_KEY")
            root().use { it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", other).toString() } shouldBe "0"
        }

        Then("NIT-5 MASTER proposal requester ≠ session requester → integrity violation, no approval row (an approval is never attached to someone else's proposal)") {
            val b = f.newBusinessId().also { f.state(it) }
            f.proposerOverride = "admin:adm_01JC0000000000000000000XX1"
            f.request(base.operator(), b) shouldBe BusinessChangeRequestOutcome.IntegrityViolation
            root().use { it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", b).toString() } shouldBe "0"
            businessAudits(b).last() shouldBe "APPROVAL_INTEGRITY_VIOLATION|FAILED|kind=BUSINESS_CRITICAL_CHANGE;column=requested_by"
        }
    }

    Given("approve — revalidation check, A step, TX1 and C") {
        Then("success: EXECUTING (revalidated=Y) → MASTER body = businessId·baseVersion·proposalId·contentDigest with a mop1 fingerprint → EXECUTED") {
            val (id, b) = f.requested()
            base.executeReplies[id] = f.succeeded(b)
            f.approve(id) shouldBe ApprovalOutcome.Executed(id)
            status(id) shouldBe "EXECUTED|-"
            val body = json.readTree(StubMasterOpsServer.requestsForPath(EXECUTE).last { base.idOf(it.body) == id }.body)
            listOf(body.get("operation").asString(), body.get("businessId").asString(), body.get("baseVersion").asString(), body.get("proposalId").asString(), body.get("contentDigest").asString(),
                body.has("registrationNumber")) shouldBe listOf("ApplyBusinessCriticalChange", b, "0", f.proposalIdOf(b), DIGEST, false)
            body.get("approvalFingerprint").asString().matches(Regex("^mop1:[0-9a-f]{64}$")) shouldBe true
            audits(id).map { it.substringBefore('|') } shouldBe listOf("APPROVAL_REQUESTED", "APPROVAL_EXECUTING", "APPROVAL_EXECUTED")
            audits(id)[1] shouldBe "APPROVAL_EXECUTING|EXECUTING|$b|kind=BUSINESS_CRITICAL_CHANGE;fields=displayName;rep=N;subject=N;evidence=PRESENT;revalidated=Y"
        }

        Then("⑤ no revalidation check → 400 before TOTP: PENDING, MASTER not called, the same TOTP still works afterwards") {
            val (id, b) = f.requested()
            base.executeReplies[id] = f.succeeded(b)
            val approver = base.approver()
            val totp = com.paycore.admin.support.TestTotp.code(approver.secret)
            f.approve(id, approver, revalidated = false, totp = totp) shouldBe ApprovalOutcome.Rejected("INVALID_INPUT", "revalidated", "REQUIRED")
            listOf(status(id), executes(id)) shouldBe listOf("PENDING|-", 0)
            f.approve(id, approver, totp = totp) shouldBe ApprovalOutcome.Executed(id)
        }

        Then("② A step unavailable → 503 (PENDING); ⑧ version moved or INACTIVE → INVALIDATED(TARGET_CHANGED) without MASTER execution") {
            val (a, ba) = f.requested()
            f.state(ba, reply = Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}"))
            f.approve(a) shouldBe ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE")
            status(a) shouldBe "PENDING|-"
            val (v, bv) = f.requested()
            f.state(bv, version = 1)
            f.approve(v) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
            val (i, bi) = f.requested()
            f.state(bi, status = "INACTIVE")
            f.approve(i) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
            listOf(executes(v), executes(i), status(v), status(i)) shouldBe listOf(0, 0, "INVALIDATED|TARGET_CHANGED", "INVALIDATED|TARGET_CHANGED")
        }

        Then("⑪ MASTER stored rejection → OWNER_REJECTED(code); a foreign success reference → EXECUTION_UNKNOWN + REFERENCE_MISMATCH (not EXECUTED)") {
            val (r, _) = f.requested()
            base.executeReplies[r] = { Reply(200, StubMasterOpsServer.rejected("PROPOSAL_REQUESTER_MISMATCH")) }
            f.approve(r) shouldBe ApprovalOutcome.OwnerRejected("PROPOSAL_REQUESTER_MISMATCH")
            val (x, _) = f.requested()
            base.executeReplies[x] = f.succeeded(f.newBusinessId())
            f.approve(x) shouldBe ApprovalOutcome.Executing(x, "EXECUTION_UNKNOWN")
            status(x) shouldBe "EXECUTION_UNKNOWN|-"
        }
    }

    Given("recovery (m-7)") {
        Then("lost response → EXECUTION_UNKNOWN → decision DECIDED → EXECUTED; the PAYMENT client is never reached") {
            val (id, b) = f.requested()
            base.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
            f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            val fingerprint = root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) as String }
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint, reference = b, operation = "ApplyBusinessCriticalChange"))
            val paymentBefore = StubPaymentOpsServer.received.size
            run(id)
            status(id) shouldBe "EXECUTED|-"
            StubPaymentOpsServer.received.size shouldBe paymentBefore
        }
    }

    Given("original proxy (M-1, R2-m4)") {
        Then("APPROVER and the requester see the raw content (one audit per view); another OPERATOR is 403 (audited); a closed request is 409 APPROVAL_NOT_OPEN without MASTER") {
            val operator = base.operator()
            val (id, b) = f.requested(operator)
            f.original(b)
            val approver = base.approver()
            (service.original(approver.account.id.value, id, path.context()) as com.paycore.admin.approval.application.provided.BusinessChangeOriginalResult.Found).content["displayName"] shouldBe BusinessChangeApprovalFixture.NEW_NAME
            (service.original(operator.id.value, id, path.context()) is com.paycore.admin.approval.application.provided.BusinessChangeOriginalResult.Found) shouldBe true
            service.original(base.operator().id.value, id, path.context()) shouldBe com.paycore.admin.approval.application.provided.BusinessChangeOriginalResult.Forbidden
            audits(id).filter { it.startsWith("APPROVAL_PROPOSAL_UNMASKED") } shouldBe listOf(
                "APPROVAL_PROPOSAL_UNMASKED|OK|$b|kind=BUSINESS_CRITICAL_CHANGE", "APPROVAL_PROPOSAL_UNMASKED|OK|$b|kind=BUSINESS_CRITICAL_CHANGE",
                "APPROVAL_PROPOSAL_UNMASKED|DENIED|$b|kind=BUSINESS_CRITICAL_CHANGE;reason=FORBIDDEN",
            )
            audits(id).joinToString() shouldNotContain CANARY_PERSON
            base.executeReplies[id] = f.succeeded(b)
            f.approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            val reads = StubMasterOpsServer.requestsForPath(BusinessChangeApprovalFixture.originalPath(b)).size
            service.original(approver.account.id.value, id, path.context()) shouldBe com.paycore.admin.approval.application.provided.BusinessChangeOriginalResult.NotOpen
            StubMasterOpsServer.requestsForPath(BusinessChangeApprovalFixture.originalPath(b)).size shouldBe reads
            audits(id).last() shouldBe "APPROVAL_PROPOSAL_UNMASKED|DENIED|$b|kind=BUSINESS_CRITICAL_CHANGE;reason=APPROVAL_NOT_OPEN"
        }
    }

    Given("V17·V18 (§3.1)") {
        Then("runtime account cannot UPDATE or DELETE the command row (1142); a BUSINESS target outside biz_ format or another kind is 3819") {
            val (id, _) = f.requested()
            com.paycore.admin.support.AdminDbAccounts.connect(container, com.paycore.admin.support.AdminDbAccounts.ADMIN_RUNTIME_USER).use { c ->
                listOf(
                    c.errorCodeOf("UPDATE admin_business_change_command SET evidence_ref = 'x' WHERE approval_id = ?", id),
                    c.errorCodeOf("DELETE FROM admin_business_change_command WHERE approval_id = ?", id),
                )
            } shouldBe listOf(1142, 1142)
            root().use { c ->
                listOf(
                    c.errorCodeOf("UPDATE paycore_admin.admin_approval_request SET target_ref = 'mer_01JC00000000000000000000M1' WHERE approval_id = ?", id),
                    c.errorCodeOf("UPDATE paycore_admin.admin_approval_request SET target_security_version = 1 WHERE approval_id = ?", id),
                )
            } shouldBe listOf(3819, 3819)
        }
    }

    Given("pending SPI (§3.4·§5.2 — m-2)") {
        Then("OPERATOR·APPROVER see {status, requestedAt}; VIEWER·SECURITY_ADMIN see null; hasUnresolved follows the unresolved key") {
            val (_, b) = f.requested()
            pending.pendingFor(base.approver().account.id.value, b)?.status shouldBe "PENDING"
            pending.pendingFor(base.operator().id.value, b)?.status shouldBe "PENDING"
            listOf(AdminRole.VIEWER, AdminRole.SECURITY_ADMIN).map { pending.pendingFor(path.withRolesViaApproval(it).id.value, b) } shouldBe listOf(null, null)
            listOf(pending.hasUnresolved(b), pending.hasUnresolved(f.newBusinessId())) shouldBe listOf(true, false)
        }
    }
    Given("S3 review MAJOR-T1 — approval stages (each failure: result, state, audit, MASTER call)") {
        Then("① requester = approver (OPERATOR + APPROVER account) → Refused(SELF_APPROVAL) before TOTP: PENDING, no POST, one REFUSED audit; the same TOTP then approves another request") {
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val (id, b) = f.requested(storage.load(both.account.id))
            base.executeReplies[id] = f.succeeded(b)
            val totp = TestTotp.code(both.secret)
            f.approve(id, both, totp = totp) shouldBe ApprovalOutcome.Refused("SELF_APPROVAL")
            listOf(status(id), executes(id)) shouldBe listOf("PENDING|-", 0)
            audits(id).last() shouldBe "APPROVAL_DENIED|REFUSED|$b|kind=BUSINESS_CRITICAL_CHANGE;reason=SELF_APPROVAL"
            val (other, ob) = f.requested()
            base.executeReplies[other] = f.succeeded(ob)
            f.approve(other, both, totp = totp) shouldBe ApprovalOutcome.Executed(other)
        }

        Then("② wrong TOTP → StepUpFailed: TX1 rolled back (PENDING, no execution row, no EXECUTING audit), the failure is recorded in a separate TX, MASTER not called") {
            val (id, b) = f.requested()
            base.executeReplies[id] = f.succeeded(b)
            val approver = base.approver()
            val good = TestTotp.code(approver.secret)
            val wrong = ((good.toInt() + 500_000) % 1_000_000).toString().padStart(6, '0')
            val before = account(approver.account.id.value)
            f.approve(id, approver, totp = wrong) shouldBe ApprovalOutcome.StepUpFailed
            listOf(status(id), executes(id), audits(id).map { it.substringBefore('|') }) shouldBe listOf("PENDING|-", 0, listOf("APPROVAL_REQUESTED"))
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_owner_execution WHERE approval_id = ?", id) as Number).toLong() } shouldBe 0L
            account(approver.account.id.value) shouldBe before.substringBefore('|') + "|" + (before.substringAfter('|').toInt() + 1)
            authFailures(approver.account.id.value) shouldBe 1L
        }

        Then("③ the requester lost OPERATOR after requesting → INVALIDATED(REQUESTER_NOT_ELIGIBLE), MASTER not called, one value-free INVALIDATED audit") {
            val operator = base.operator()
            val (id, b) = f.requested(operator)
            base.executeReplies[id] = f.succeeded(b)
            root().use { it.update("UPDATE admin_account SET active_roles = '' WHERE actor_id = ?", operator.id.value) }
            f.approve(id) shouldBe ApprovalOutcome.Invalidated("REQUESTER_NOT_ELIGIBLE")
            listOf(status(id), executes(id)) shouldBe listOf("INVALIDATED|REQUESTER_NOT_ELIGIBLE", 0)
            audits(id).last() shouldBe "APPROVAL_INVALIDATED|INVALIDATED|$b|kind=BUSINESS_CRITICAL_CHANGE;fields=displayName;rep=N;subject=N;evidence=PRESENT;reason=REQUESTER_NOT_ELIGIBLE"
        }

        Then("⑤ recovery past the deadline: confirmation cannot settle it (409) → one OWNER_UNRESOLVABLE finding over two cycles, still EXECUTION_UNKNOWN, one execution POST, PAYMENT untouched") {
            val (id, b) = f.requested()
            base.executeReplies[id] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") }
            f.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
            pastDeadline(id)
            base.deadlineReplies[id] = { Reply(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\"}") }
            val paymentBefore = StubPaymentOpsServer.received.size
            run(id)
            run(id)
            listOf(status(id), executes(id)) shouldBe listOf("EXECUTION_UNKNOWN|-", 1)
            findings(id) shouldBe listOf("OWNER_UNRESOLVABLE|$b|IDEMPOTENCY_KEY_REUSED")
            StubPaymentOpsServer.received.size shouldBe paymentBefore
        }
    }

    Given("S3 review MAJOR-T1 ⑥⑦ — original proxy closed states, rate limit") {
        fun closed(id: String, b: String) {
            f.original(b)
            val reads = originalReads(b)
            withClue(status(id)) {
                service.original(base.approver().account.id.value, id, path.context()) shouldBe BusinessChangeOriginalResult.NotOpen
                originalReads(b) shouldBe reads
                audits(id).last() shouldBe "APPROVAL_PROPOSAL_UNMASKED|DENIED|$b|kind=BUSINESS_CRITICAL_CHANGE;reason=APPROVAL_NOT_OPEN"
            }
        }
        Then("REJECTED · WITHDRAWN · EXPIRED · INVALIDATED · OWNER_REJECTED · EXECUTED → each 409 APPROVAL_NOT_OPEN, MASTER original not called, one DENIED audit") {
            val (rejected, rb) = f.requested()
            val approver = base.approver()
            approvals.reject(DecideApproval(rejected, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, TestTotp.code(approver.secret), path.context()))
            val operator = base.operator()
            val (withdrawn, wb) = f.requested(operator)
            approvals.withdraw(WithdrawApproval(withdrawn, operator.id.value, storage.load(operator.id).sessionGeneration, path.context()))
            val (expired, eb) = f.requested()
            root().use { it.update("UPDATE admin_approval_request SET requested_at = UTC_TIMESTAMP(3) - INTERVAL 2 DAY, expires_at = UTC_TIMESTAMP(3) - INTERVAL 1 DAY WHERE approval_id = ?", expired) }
            f.approve(expired) shouldBe ApprovalOutcome.Expired
            val (invalidated, ib) = f.requested()
            f.state(ib, version = 1)
            f.approve(invalidated) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
            val (ownerRejected, ob) = f.requested()
            base.executeReplies[ownerRejected] = { Reply(200, StubMasterOpsServer.rejected("BUSINESS_VERSION_CONFLICT")) }
            f.approve(ownerRejected) shouldBe ApprovalOutcome.OwnerRejected("BUSINESS_VERSION_CONFLICT")
            val (executed, xb) = f.requested()
            base.executeReplies[executed] = f.succeeded(xb)
            f.approve(executed) shouldBe ApprovalOutcome.Executed(executed)
            listOf(rejected, withdrawn, expired, invalidated, ownerRejected, executed).map { status(it).substringBefore('|') } shouldBe
                listOf("REJECTED", "WITHDRAWN", "EXPIRED", "INVALIDATED", "OWNER_REJECTED", "EXECUTED")
            listOf(rejected to rb, withdrawn to wb, expired to eb, invalidated to ib, ownerRejected to ob, executed to xb).forEach { (id, b) -> closed(id, b) }
        }

        Then("⑦ the shared business read window: once exhausted → RateLimited (429) without a MASTER call, audited RATE_LIMITED") {
            val operator = base.operator()
            val (id, b) = f.requested(operator)
            f.original(b)
            val outcomes = mutableListOf<BusinessChangeOriginalResult>()
            while (outcomes.size < 200 && outcomes.lastOrNull().let { it == null || it is BusinessChangeOriginalResult.Found }) outcomes += service.original(operator.id.value, id, path.context())
            outcomes.last() shouldBe BusinessChangeOriginalResult.RateLimited
            val found = outcomes.count { it is BusinessChangeOriginalResult.Found }
            (found >= 1) shouldBe true
            originalReads(b) shouldBe found
            audits(id).last() shouldBe "APPROVAL_PROPOSAL_UNMASKED|RATE_LIMITED|$b|kind=BUSINESS_CRITICAL_CHANGE"
        }
    }

    Given("S3 review NIT-1 — original proxy: unknown / malformed / other-kind approval ids are 404 with no audit and no MASTER call") {
        Then("malformed id, an unknown well-formed id and a merchant approval id → NotFound; no UNMASKED audit row") {
            val approver = base.approver().account.id.value
            val (merchantApproval, _) = base.requested()
            val readsBefore = StubMasterOpsServer.received.count { it.path.endsWith("/original") }
            listOf("apr_bad", "apr_01JC0000000000000000000ZZ9", merchantApproval).map { service.original(approver, it, path.context()) } shouldBe
                List(3) { BusinessChangeOriginalResult.NotFound }
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE action = 'APPROVAL_PROPOSAL_UNMASKED' AND approval_id IN (?, ?, ?)", "apr_bad", "apr_01JC0000000000000000000ZZ9", merchantApproval) as Number).toLong() } shouldBe 0L
            StubMasterOpsServer.received.count { it.path.endsWith("/original") } shouldBe readsBefore
        }
    }

    Given("S3 review MAJOR-T1 ⑧ — the unresolved key UNIQUE is authoritative (real MySQL, two connections)") {
        Then("two requests both past the advisory pre-check (both reach MASTER) → exactly one approval + command row; the other 409 DUPLICATE_UNRESOLVED with one DENIED audit") {
            val b = f.newBusinessId().also { f.state(it) }
            val barrier = java.util.concurrent.CyclicBarrier(2)
            f.proposalReplies[b] = { body ->
                barrier.await(20, java.util.concurrent.TimeUnit.SECONDS)
                Reply(200, f.proposed(body))
            }
            val operators = listOf(base.operator(), base.operator())
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            val outcomes = try {
                operators.map { op -> pool.submit<BusinessChangeRequestOutcome> { f.request(op, b) } }.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
                f.proposalReplies.remove(b)
            }
            proposals(b) shouldBe 2
            outcomes.count { it is BusinessChangeRequestOutcome.Created } shouldBe 1
            outcomes.filterNot { it is BusinessChangeRequestOutcome.Created } shouldBe listOf(BusinessChangeRequestOutcome.Conflict("DUPLICATE_UNRESOLVED"))
            listOf(rows(b), commandRows(b)) shouldBe listOf(1L, 1L)
            businessAudits(b).filter { it.startsWith("APPROVAL_DENIED") } shouldBe listOf("APPROVAL_DENIED|DENIED|kind=BUSINESS_CRITICAL_CHANGE;reason=DUPLICATE_UNRESOLVED")
        }
    }

    Given("S3 review MINOR-1·MINOR-2·Q3·⑩ — request edge cases") {
        Then("MINOR-1 create → withdraw → resend the same submissionId (MASTER replays the same proposal) → 409 PROPOSAL_ALREADY_USED + one DENIED audit; no second approval or command row") {
            val operator = base.operator()
            val b = f.newBusinessId().also { f.state(it) }
            val submission = java.util.UUID.randomUUID().toString().replace("-", "")
            val first = f.request(operator, b, submissionId = submission) as BusinessChangeRequestOutcome.Created
            approvals.withdraw(WithdrawApproval(first.approvalId, operator.id.value, storage.load(operator.id).sessionGeneration, path.context())) shouldBe ApprovalOutcome.Closed("WITHDRAWN")
            f.request(operator, b, submissionId = submission) shouldBe BusinessChangeRequestOutcome.Conflict("PROPOSAL_ALREADY_USED")
            listOf(rows(b), commandRows(b)) shouldBe listOf(1L, 1L)
            businessAudits(b).last() shouldBe "APPROVAL_DENIED|DENIED|kind=BUSINESS_CRITICAL_CHANGE;reason=PROPOSAL_ALREADY_USED"
        }

        Then("MINOR-2 MASTER proposal with duplicated changedFields is unreadable (strict shape) → 503 MASTER_OUTCOME_UNKNOWN, no approval row") {
            val b = f.newBusinessId().also { f.state(it) }
            f.proposalFields = "\"displayName\",\"displayName\""
            f.request(base.operator(), b) shouldBe BusinessChangeRequestOutcome.Unavailable("MASTER_OUTCOME_UNKNOWN")
            listOf(rows(b), commandRows(b)) shouldBe listOf(0L, 0L)
        }

        Then("Q3 order: an unresolved request exists and the new body violates the format → 400 (input rules first), no DENIED audit, MASTER not called") {
            val (_, b) = f.requested()
            val before = listOf(proposals(b), businessAudits(b).size)
            f.request(base.operator(), b, reason = null) shouldBe BusinessChangeRequestOutcome.Invalid(listOf(BusinessFieldError("reason", "REQUIRED")))
            listOf(proposals(b), businessAudits(b).size) shouldBe before
        }

        Then("⑩ audit detail follows MASTER's flags: rep=Y only for a critical representative change, subject=Y for a subject correction; fields are names only") {
            f.proposalFields = "\"businessType\",\"representatives\""
            f.representativeChanged = true
            f.subjectCorrection = true
            val (id, b) = f.requested()
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|SUCCESS|$b|kind=BUSINESS_CRITICAL_CHANGE;fields=businessType,representatives;rep=Y;subject=Y;evidence=PRESENT")
            root().use { it.scalar("SELECT CONCAT(representative_changed, '|', subject_correction) FROM admin_business_change_command WHERE approval_id = ?", id) } shouldBe "1|1"
            f.representativeChanged = false
            f.subjectCorrection = false
            val (minor, mb) = f.requested()
            audits(minor) shouldBe listOf("APPROVAL_REQUESTED|SUCCESS|$mb|kind=BUSINESS_CRITICAL_CHANGE;fields=businessType,representatives;rep=N;subject=N;evidence=PRESENT")
        }
    }
})

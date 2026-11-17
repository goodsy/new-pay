package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.MerchantCodeChangeApprovalFixture.Companion.CURRENT
import com.paycore.admin.approval.MerchantCodeChangeApprovalFixture.Companion.DETAIL
import com.paycore.admin.approval.MerchantCodeChangeApprovalFixture.Companion.NEW
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.approval.domain.ApprovalKind
import com.paycore.admin.approval.domain.ApprovalRequest
import com.paycore.admin.approval.domain.ApprovalTarget
import com.paycore.admin.approval.domain.ApprovalTargetKind
import com.paycore.admin.merchantops.application.provided.MerchantPendingApprovals
import com.paycore.admin.merchantops.application.provided.PendingMerchantApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * MERCHANT-CODE-001 §8 MC-1b-7·8(ADMIN 절반 — §5.2·§5.3·§5.5, D-MCODE-002 Q-MC-7·Q-MC-10). 실제 MySQL 8.4, MASTER는 실제 HTTP 대역. 명시 Fixture [MerchantCodeChangeApprovalFixture].
 * 요청 advisory 409(계약 승인 뒤·이미 변경), 같은 그룹 미해결(계약 승인 요청) 409 vs SITE_REVIEW 공존 201, A 단계 currentCode·변경 가능 변화 INVALIDATED(MASTER 미호출), §9.5 응답 매핑·회복·응답 유실,
 * 감사 detail `from=;to=;reason=`·target_ref = mer_(C-21), 새 행 `target_merchant_id` = mer_(가맹점 대상 6개 요청 경로 각각 — 리뷰 m-3). 결정 고정(Q-MC-10): 등록자 = 승인자도 EXECUTED.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantCodeChangeApprovalServiceSpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    pending: MerchantPendingApprovals,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    registry: MeterRegistry,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = MerchantCodeChangeApprovalFixture(base, merchants, path, storage)
    val activation = MerchantActivationApprovalFixture(base, merchants, path, storage)
    val currency = MerchantCurrencyApprovalFixture(base, merchants, path, storage)
    f.install()
    activation.install()
    currency.install()
    afterSpec {
        f.uninstall()
        activation.uninstall()
        currency.uninstall()
    }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun audits(id: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) }
    // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.8 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 행은 내부 키(target_merchant_key·COALESCE(target_merchant_id, target_ref))로 찾는다
    fun merchantAudits(m: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_merchant_key = ? ORDER BY event_id", m) }
    fun targetMerchant(id: String) = root().use { it.scalar("SELECT IFNULL(target_merchant_id, '-') FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun storedBody(id: String) = root().use { it.scalar("SELECT request_body FROM admin_owner_execution WHERE approval_id = ?", id) as ByteArray }
    fun fingerprint(id: String) = root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) as String }
    fun posts(id: String) = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANT_CODE_CHANGES).filter { base.idOf(it.body) == id }
    fun pinned(id: String) = json.readTree(root().use { it.scalar("SELECT pinned_target FROM admin_merchant_ops_command WHERE approval_id = ?", id) as String })
    fun alerts(code: String) = registry.find("paycore.admin.ops.integrity_alert").tags("code", code).counters().sumOf { it.count() }
    fun run(id: String) {
        root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
        recovery.recoverDue()
    }
    fun lost(): Pair<String, String> {
        val (id, m) = f.requested()
        base.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
        base.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
        return id to m
    }

    Given("MC-1b-7 code change request (OPERATOR, evidence required, group MERCHANT:<mer_> — C-15)") {
        Then("row MERCHANT_CODE_CHANGE / target_ref = mer_ / target_merchant_id = mer_ / key MERCHANT:<mer_>; pinned 7 keys (no registrant key); one audit with from=;to=;reason=") {
            val (id, m) = f.requested()
            root().use {
                it.scalar(
                    "SELECT CONCAT(r.kind, '|', r.status, '|', r.target_ref, '|', r.target_merchant_id, '|', r.unresolved_key, '|', c.command_kind, '|', c.evidence_ref) " +
                        "FROM admin_approval_request r JOIN admin_merchant_ops_command c ON c.approval_id = r.approval_id WHERE r.approval_id = ?", id,
                )
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §8 MC-3-20·§5.5 배치 경계 — C-21(코드 변경 승인 target_ref = mer_)은 "MC-3 전까지"였다: MC-3a부터 요청 시점(옛) 코드(내부 키 target_merchant_id = mer_)
            } shouldBe "MERCHANT_CODE_CHANGE|PENDING|$CURRENT|$m|MERCHANT:$m|MERCHANT_CODE_CHANGE|${MerchantCodeChangeApprovalFixture.EVIDENCE}"
            pinned(id).propertyNames().toSet() shouldBe setOf("merchantId", "merchantName", "currentCode", "newCode", "reasonCode", "contractApproved", "codeChangeCount")
            listOf(pinned(id).get("currentCode").asString(), pinned(id).get("newCode").asString(), pinned(id).get("contractApproved").asBoolean(), pinned(id).get("codeChangeCount").asInt()) shouldBe
                listOf(CURRENT, NEW, false, 0)
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §8 MC-3-20·§5.5 배치 경계 — C-21(코드 변경 승인 target_ref = mer_)은 "MC-3 전까지"였다: MC-3a부터 요청 시점(옛) 코드(내부 키 target_merchant_id = mer_)
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|$CURRENT|kind=MERCHANT_CODE_CHANGE;$DETAIL")
        }
        Then("400 before MASTER: uppercase / short code, unknown reason, missing evidence; new = current is 400 after the detail read; non-OPERATOR denied") {
            val operator = base.operator()
            val m = base.newMerchantId().also { f.detail(it) }
            listOf(
                f.request(operator, m, newCode = "ShopNew2026"), f.request(operator, m, newCode = "shop1"), f.request(operator, m, reason = "CHANGED_MIND"),
                f.request(operator, m, evidence = ""), f.request(operator, m, newCode = CURRENT), f.request(storage.load(base.approver().account.id), m),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("INVALID_INPUT", "newCode", "FORMAT"), ApprovalOutcome.Rejected("INVALID_INPUT", "newCode", "FORMAT"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "reasonCode", "NOT_ALLOWED"), ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "newCode", "SAME_AS_CURRENT"), ApprovalOutcome.Denied,
            )
            // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.8 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 행은 내부 키(target_merchant_key·COALESCE(target_merchant_id, target_ref))로 찾는다
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE COALESCE(target_merchant_id, target_ref) = ?", m) as Number).toLong() } shouldBe 0
        }
        Then("advisory 409 TARGET_NOT_ELIGIBLE_ADVISORY: after the first contract approval (changeable false), already changed once; 404 unknown; 503 MASTER down") {
            val operator = base.operator()
            val locked = base.newMerchantId().also { f.detail(it, changeable = false) }
            val changed = base.newMerchantId().also { f.changedDetail(it) }
            val unknown = base.newMerchantId()
            val down = base.newMerchantId().also { StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(it), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}")) }
            listOf(f.request(operator, locked), f.request(operator, changed, newCode = "shopother01"), f.request(operator, unknown), f.request(operator, down)) shouldBe listOf(
                ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("MERCHANT_NOT_FOUND"),
                ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE"),
            )
            merchantAudits(locked) shouldBe listOf("APPROVAL_DENIED|kind=MERCHANT_CODE_CHANGE;reason=MERCHANT_CODE_NOT_CHANGEABLE")
        }
        Then("same group: a pending contract approval makes the code change request 409 DUPLICATE_UNRESOLVED; control — a pending SITE_REVIEW coexists (201)") {
            val (_, m) = base.requested()
            f.detail(m)
            f.request(base.operator(), m) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            val (_, s) = activation.requestedSiteReview()
            f.detail(s)
            (f.request(base.operator(), s) is ApprovalOutcome.Created) shouldBe true
        }
    }

    Given("MC-1b-7 code change approval (APPROVER + TOTP → TX1 → POST merchant-code-changes → TX2)") {
        Then("200 mer_ → EXECUTED; the 14-field body (operation ChangeMerchantCode, currentCode = pinned); posted bytes = stored bytes; every audit row carries from=;to=;reason= and target_ref = mer_") {
            val operator = base.operator()
            val (id, m) = f.requested(operator)
            base.executeReplies[id] = base.succeeded(m)
            val approver = base.approver()
            base.approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            val post = posts(id).single()
            val body = json.readTree(post.body)
            body.propertyNames().toList() shouldBe listOf(
                "idempotencyKey", "correlationId", "approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "evidenceRef", "approvalFingerprint", "operation",
                "merchantId", "currentCode", "newCode", "reasonCode",
            )
            listOf("operation", "merchantId", "currentCode", "newCode", "reasonCode", "requestedBy", "approvedBy").map { body.get(it).asString() } shouldBe listOf(
                "ChangeMerchantCode", m, CURRENT, NEW, "TYPO_CORRECTION", "admin:${operator.id.value}", "admin:${approver.account.id.value}",
            )
            post.body.contentEquals(storedBody(id)) shouldBe true
            status(id) shouldBe "EXECUTED|-"
            audits(id) shouldBe listOf(
                // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §8 MC-3-20·§5.5 배치 경계 — C-21(코드 변경 승인 target_ref = mer_)은 "MC-3 전까지"였다: MC-3a부터 요청 시점(옛) 코드(내부 키 target_merchant_id = mer_)
                "APPROVAL_REQUESTED|$CURRENT|kind=MERCHANT_CODE_CHANGE;$DETAIL", "APPROVAL_EXECUTING|$CURRENT|kind=MERCHANT_CODE_CHANGE;$DETAIL",
                "APPROVAL_EXECUTED|$CURRENT|kind=MERCHANT_CODE_CHANGE;$DETAIL",
            )
        }
        Then("D-MCODE-002 Q-MC-10 pinning: the approver is the merchant registrant (≠ requester) → TX1 passes, one MASTER call, EXECUTED (no 403 APPROVER_IS_REGISTRANT)") {
            val approver = base.approver()
            val registrant = "admin:${approver.account.id.value}"
            val (id, m) = f.requested()
            // MASTER 상세의 seq 1 배정자 = 원 가맹점 등록자(같은 승인자 계정) — ADMIN은 이 값을 승인 판정에 쓰지 않는다.
            f.detail(m, history = StubMasterOpsServer.codeHistoryJson(CURRENT).replace("admin:adm_01JC00000000000000000000V1", registrant))
            base.executeReplies[id] = base.succeeded(m)
            base.approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            posts(id).size shouldBe 1
            audits(id).none { it.contains("APPROVER_IS_REGISTRANT") } shouldBe true
        }
        Then("control: requester = approver (an OPERATOR + APPROVER account approving its own request) → refused before TOTP (the same code then approves another request), no POST") {
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val (id, _) = f.requested(storage.load(both.account.id))
            val code = com.paycore.admin.support.TestTotp.code(both.secret)
            base.approve(id, both, totp = code) shouldBe ApprovalOutcome.Refused("SELF_APPROVAL")
            posts(id).size shouldBe 0
            status(id) shouldBe "PENDING|-"
            // 리뷰 MINOR-2(MC-1b-7): 거절은 TOTP 소비 전 — 같은 계정이 **같은 코드**로 다른 운영자의 요청을 승인하면 통과한다(소비됐다면 STEP_UP_FAILED).
            val (other, m) = f.requested()
            base.executeReplies[other] = base.succeeded(m)
            base.approve(other, both, totp = code) shouldBe ApprovalOutcome.Executed(other)
        }
        Then("A-step change → INVALIDATED(TARGET_CHANGED) with no POST: current code changed, no longer changeable (contract approved), merchant gone; MASTER down → 503 PENDING") {
            val (codeMoved, m1) = f.requested()
            f.detail(m1, code = "shopmoved01")
            val (locked, m2) = f.requested()
            f.detail(m2, changeable = false)
            val (gone, m3) = f.requested()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m3), Reply(404, "{\"error\":\"MERCHANT_NOT_FOUND\"}"))
            listOf(base.approve(codeMoved), base.approve(locked), base.approve(gone)) shouldBe List(3) { ApprovalOutcome.Invalidated("TARGET_CHANGED") }
            val (down, m4) = f.requested()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m4), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            base.approve(down) shouldBe ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE")
            listOf(codeMoved, locked, gone, down).sumOf { posts(it).size } shouldBe 0
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §8 MC-3-20·§5.5 배치 경계 — C-21(코드 변경 승인 target_ref = mer_)은 "MC-3 전까지"였다: MC-3a부터 요청 시점(옛) 코드(내부 키 target_merchant_id = mer_)
            audits(codeMoved).last() shouldBe "APPROVAL_INVALIDATED|$CURRENT|kind=MERCHANT_CODE_CHANGE;reason=TARGET_CHANGED;$DETAIL"
        }
        Then("MASTER TAKEN / LOCKED / ALREADY_CHANGED → OWNER_REJECTED; MISMATCH → OWNER_REJECTED + integrity alert; a foreign mer_ reference → EXECUTION_UNKNOWN") {
            listOf("MERCHANT_CODE_TAKEN", "MERCHANT_CODE_LOCKED", "MERCHANT_CODE_ALREADY_CHANGED").forEach { code ->
                val (id, _) = f.requested()
                base.executeReplies[id] = { Reply(200, StubMasterOpsServer.rejected(code)) }
                withClue(code) { base.approve(id) shouldBe ApprovalOutcome.OwnerRejected(code) }
            }
            val before = alerts("MERCHANT_CODE_MISMATCH")
            val (mismatch, _) = f.requested()
            base.executeReplies[mismatch] = { Reply(200, StubMasterOpsServer.rejected("MERCHANT_CODE_MISMATCH")) }
            base.approve(mismatch) shouldBe ApprovalOutcome.OwnerRejected("MERCHANT_CODE_MISMATCH")
            alerts("MERCHANT_CODE_MISMATCH") shouldBe before + 1.0
            val (foreign, _) = f.requested()
            base.executeReplies[foreign] = base.succeeded(base.newMerchantId())
            base.approve(foreign) shouldBe ApprovalOutcome.Executing(foreign, "EXECUTION_UNKNOWN")
        }
    }

    Given("MC-1b-7 recovery / lost response (§9.5 — kind branch only)") {
        Then("lost response: NONE resends the same stored bytes; DECIDED mer_ (ChangeMerchantCode, same mop1) closes EXECUTED") {
            val (id, m) = lost()
            run(id)
            val sent = posts(id).map { it.body }
            sent.size shouldBe 2
            sent[1].contentEquals(storedBody(id)) shouldBe true
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = m, operation = "ChangeMerchantCode"))
            run(id)
            status(id) shouldBe "EXECUTED|-"
            audits(id).last() shouldContain DETAIL
        }
        Then("past the deadline: confirmation only with the stored bytes (operation ChangeMerchantCode); APPROVAL_EXPIRED → OWNER_REJECTED") {
            val (id, _) = lost()
            root().use {
                it.update("UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, execute_before = UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE WHERE approval_id = ?", id)
            }
            base.deadlineReplies[id] = { Reply(200, StubMasterOpsServer.rejected("APPROVAL_EXPIRED")) }
            run(id)
            status(id) shouldBe "OWNER_REJECTED|APPROVAL_EXPIRED"
            val confirmed = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.DEADLINE_CONFIRMATIONS).single { base.idOf(it.body) == id }.body
            confirmed.contentEquals(storedBody(id)) shouldBe true
            json.readTree(confirmed).get("operation").asString() shouldBe "ChangeMerchantCode"
        }
        Then("a decision naming another operation is unreadable for ChangeMerchantCode → stays EXECUTION_UNKNOWN") {
            val (id, m) = lost()
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = m, operation = "EnableCurrency"))
            run(id)
            status(id) shouldBe "EXECUTION_UNKNOWN|-"
        }
    }

    Given("MC-1b-8 unresolved key on mer_ (§5.2) and the target_merchant_id generator (review m-3)") {
        Then("after the code change executes, a new SITE_REVIEW request is 409 against the SITE_REVIEW request opened before the change (same key MERCHANT:SITE_REVIEW:<mer_>)") {
            val (siteReview, m) = activation.requestedSiteReview()
            val keyBefore = root().use { it.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", siteReview) }
            f.detail(m)
            val (change, _) = f.requested(merchantId = m)
            base.executeReplies[change] = base.succeeded(m)
            base.approve(change) shouldBe ApprovalOutcome.Executed(change)
            activation.detail(m)
            activation.requestSiteReview(base.operator(), m) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            root().use { it.scalar("SELECT unresolved_key FROM admin_approval_request WHERE approval_id = ?", siteReview) } shouldBe keyBefore
            keyBefore shouldBe "MERCHANT:SITE_REVIEW:$m"
            pending.pendingFor(base.operator().id.value, m).map { it.kind } shouldBe listOf("MERCHANT_SITE_REVIEW_APPROVAL")
        }
        Then("the unresolved_key generation expression keys merchant rows on COALESCE(target_merchant_id, target_ref) (MC-3 will put the code in target_ref)") {
            val expression = root().use {
                it.scalar(
                    "SELECT GENERATION_EXPRESSION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'admin_approval_request' AND COLUMN_NAME = 'unresolved_key'",
                ) as String
            }.lowercase().replace(" ", "")
            expression shouldContain "coalesce(`target_merchant_id`,`target_ref`)"
            expression.split("coalesce(`target_merchant_id`,`target_ref`)").size - 1 shouldBe 8
        }
        Then("each merchant-target request path writes target_merchant_id = the request merchantId (contract, first MID, PM activation, site review, currency, code change)") {
            val rows = mapOf(
                "MERCHANT_CONTRACT_APPROVAL" to base.requested(),
                "MERCHANT_FIRST_MID_ISSUANCE" to base.requestedMid(),
                "MERCHANT_PAYMENT_METHOD_ACTIVATION" to activation.requestedActivation(),
                "MERCHANT_SITE_REVIEW_APPROVAL" to activation.requestedSiteReview(),
                "MERCHANT_CURRENCY_ENABLEMENT" to currency.requested(),
                "MERCHANT_CODE_CHANGE" to f.requested(),
            )
            rows.forEach { (kind, idAndMerchant) -> withClue(kind) { targetMerchant(idAndMerchant.first) shouldBe idAndMerchant.second } }
        }
        Then("the single factory: every MERCHANT-target kind in ApprovalKind gets merchantKey = merchantId (kinds without a request path yet inherit it — B3-3·B3-4)") {
            val m = base.newMerchantId()
            ApprovalKind.entries.filter { it.targetKind == ApprovalTargetKind.MERCHANT }.forEach { kind ->
                val r = ApprovalRequest.create("apr_01JCMC00000000000000000MC1", kind, ApprovalTarget.Merchant(m), "{}", null, null, "adm_x", "X", Instant.EPOCH, Duration.ofHours(1))
                withClue(kind) { r.target.merchantKey shouldBe m }
            }
            ApprovalTarget.Payment("pay_01JCMC00000000000000000MC1", 0).merchantKey shouldBe null
        }
    }

    Given("MC-1b views: pendingApprovals names the kind") {
        Then("the pending list shows MERCHANT_CODE_CHANGE") {
            val requester = base.operator()
            val (id, m) = f.requested(requester)
            pending.pendingFor(requester.id.value, m) shouldBe listOf(PendingMerchantApproval(id, "MERCHANT_CODE_CHANGE", "PENDING", null))
        }
    }
})

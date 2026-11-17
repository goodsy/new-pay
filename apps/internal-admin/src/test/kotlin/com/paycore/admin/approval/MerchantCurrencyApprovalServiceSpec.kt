package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.MerchantCurrencyApprovalFixture.Companion.AT_EXECUTION
import com.paycore.admin.approval.MerchantCurrencyApprovalFixture.Companion.DATE
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RecoverOwnerExecutionsUseCase
import com.paycore.admin.merchantops.application.provided.MerchantPendingApprovals
import com.paycore.admin.merchantops.application.provided.PendingMerchantApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import com.paycore.admin.support.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * B3-2 통화 활성화 승인 ADMIN 절반(MERCHANT-OPS-002 §8.4 B3-2-5·§8.3 B3-2-4 — D-MOPS-009 ②, §9.16·§9.26). 실제 MySQL 8.4, MASTER는 실제 HTTP 대역. 명시 Fixture [MerchantCurrencyApprovalFixture].
 * 행: 요청 본문 `effectiveFromMode`(`AT_EXECUTION`·`DATE`)·`effectiveFromDate`(DATE만 — 내일 이후 KST, advisory)·`effectiveToDate?`, pinned `{merchantId, merchantName, merchantAccountId, accountLifecycle,
 * currency, effectiveFromMode, effectiveFrom(DATE만), effectiveTo}`, 미리 보기 부적격 409(MID ACTIVE 아님·같은 통화 열린 기록), 그룹 미해결 키 `MERCHANT:CURRENCY:`(C-35),
 * MASTER 본문 변환(`AT_EXECUTION` 그대로·날짜 → KST 00:00 UTC 시각), **승인 TX1이 저장한 본문 바이트 = 실행·재전송 바이트**(재생성 없음 — 승인 시각과 무관하게 `AT_EXECUTION` 문자열),
 * pinned ≠ 현재 INVALIDATED, 회복(결정 조회 → 기한 확인 `operation` 본문 → 같은 바이트 재전송). 등록자 규칙 없음(1인 등록 단계 없음 — §9.27 적용 범위 밖, 요청자≠승인자만).
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantCurrencyApprovalServiceSpec(
    merchants: MerchantOpsApprovalUseCase,
    owners: OwnerServiceApprovalUseCase,
    recovery: RecoverOwnerExecutionsUseCase,
    query: ApprovalQuery,
    pending: MerchantPendingApprovals,
    path: ApprovalPath,
    ready: LoginReadyAccounts,
    storage: AdminAccountStorage,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = MerchantCurrencyApprovalFixture(base, merchants, path, storage)
    f.install()
    afterSpec { f.uninstall() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun execution(id: String) = root().use {
        it.scalar(
            "SELECT CONCAT(COALESCE(owner_reference, '-'), '|', COALESCE(owner_result_code, '-'), '|', COALESCE(integrity_flag, '-')) FROM admin_owner_execution WHERE approval_id = ?", id,
        ) as String?
    }
    fun storedBody(id: String) = root().use { it.scalar("SELECT request_body FROM admin_owner_execution WHERE approval_id = ?", id) as ByteArray }
    fun fingerprint(id: String) = root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) as String }
    fun audits(id: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) }
    // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.9 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 감사 행은 내부 키 가상 열 `target_merchant_key = ?`로 찾는다(MC-3a 전 mer_ 표기 행 포함)
    fun merchantAudits(m: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_merchant_key = ? ORDER BY event_id", m) }
    // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.9 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 승인 행은 `COALESCE(target_merchant_id, target_ref) = ?`로 찾는다
    fun rowsFor(m: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE COALESCE(target_merchant_id, target_ref) = ?", m) as Number).toLong() }
    fun detailCalls(m: String) = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).size
    fun posts(id: String) = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CURRENCY_ENABLEMENTS).filter { base.idOf(it.body) == id }
    fun row(id: String) = root().use {
        it.column(
            "SELECT CONCAT(r.kind, '|', r.status, '|', r.target_kind, '|', r.target_ref, '|', r.target_security_version, '|', r.unresolved_key, '|', c.command_kind, '|', " +
                "c.evidence_ref, '|', (c.pinned_target = r.target_snapshot)) FROM admin_approval_request r JOIN admin_merchant_ops_command c ON c.approval_id = r.approval_id " +
                "WHERE r.approval_id = ?",
            id,
        ).single()
    }
    fun pinned(id: String) = json.readTree(root().use { it.scalar("SELECT pinned_target FROM admin_merchant_ops_command WHERE approval_id = ?", id) as String })
    fun calls(id: String): String = StubMasterOpsServer.received.filter {
        it.path in setOf(StubMasterOpsServer.CURRENCY_ENABLEMENTS, StubMasterOpsServer.APPROVAL_DECISIONS, StubMasterOpsServer.DEADLINE_CONFIRMATIONS) && base.idOf(it.body) == id
    }.joinToString("") {
        when (it.path) {
            StubMasterOpsServer.CURRENCY_ENABLEMENTS -> "E"
            StubMasterOpsServer.APPROVAL_DECISIONS -> "D"
            else -> "C"
        }
    }
    fun run(id: String) {
        root().use { it.update("UPDATE admin_owner_execution SET next_attempt_at = '2000-01-01 00:00:00.000' WHERE approval_id = ?", id) }
        recovery.recoverDue()
    }
    fun lost(request: () -> Pair<String, String>): Pair<String, String> {
        val (id, m) = request()
        base.executeReplies[id] = { Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
        base.approve(id) shouldBe ApprovalOutcome.Executing(id, "EXECUTION_UNKNOWN")
        return id to m
    }

    Given("MC-1 currency enablement request (OPERATOR, evidence = institution reply reference required, C-35 group CURRENCY)") {
        Then("AT_EXECUTION: MERCHANT:CURRENCY:<id>, command CURRENCY_ENABLEMENT with pinned (MID ACTIVE, KRW, mode, no start date, no end) and the evidence; one audit without it") {
            val (id, m) = f.requested()
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            row(id) shouldBe "MERCHANT_CURRENCY_ENABLEMENT|PENDING|MERCHANT|${MerchantOpsApprovalFixture.DEFAULT_CODE}|0|MERCHANT:CURRENCY:$m|CURRENCY_ENABLEMENT|${MerchantCurrencyApprovalFixture.EVIDENCE}|1"
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §8 MC-3-20·§5.3·§5.6.4 — 가맹점 대상 봉인 5종에 표시용 merchantCode 추가(새 행 필수, 기존 행 읽기 선택 — A 단계 비교 제외 C-19)
            pinned(id).propertyNames().toSet() shouldBe setOf(
                "merchantId", "merchantName", "merchantAccountId", "accountLifecycle", "currency", "effectiveFromMode", "effectiveFrom", "effectiveTo", "merchantCode",
            )
            listOf("merchantAccountId", "accountLifecycle", "currency", "effectiveFromMode").map { pinned(id).get(it).asString() } shouldBe
                listOf(f.midOf(m), "ACTIVE", "KRW", "AT_EXECUTION")
            listOf(pinned(id).get("effectiveFrom").isNull, pinned(id).get("effectiveTo").isNull) shouldBe listOf(true, true)
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|kind=MERCHANT_CURRENCY_ENABLEMENT")
            pinned(id).toString() shouldNotContain "VAN-REPLY"
        }
        Then("DATE: pinned keeps the KST dates (start tomorrow or later, exclusive end)") {
            val from = f.tomorrowKst().plusDays(1).toString()
            val to = f.tomorrowKst().plusDays(30).toString()
            val (id, _) = f.requested(mode = DATE, fromDate = from, toDate = to)
            listOf("effectiveFromMode", "effectiveFrom", "effectiveTo").map { pinned(id).get(it).asString() } shouldBe listOf("DATE", from, to)
        }
        Then("invalid input is 400 before MASTER (USD, DATE without a date or today, AT_EXECUTION with a date, missing evidence); a non-OPERATOR is denied without a call") {
            val operator = base.operator()
            val m = base.newMerchantId().also { f.detail(it) }
            listOf(
                f.request(operator, m, currency = "USD"), f.request(operator, m, mode = DATE), f.request(operator, m, mode = DATE, fromDate = f.todayKst().toString()),
                f.request(operator, m, fromDate = f.tomorrowKst().toString()), f.request(operator, m, evidence = ""), f.request(operator, m, mode = "at_execution"),
                f.request(storage.load(base.approver().account.id), m),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("INVALID_INPUT", "currency", "NOT_ALLOWED"), ApprovalOutcome.Rejected("INVALID_INPUT", "effectiveFromDate", "REQUIRED"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "effectiveFromDate", "NOT_FUTURE"), ApprovalOutcome.Rejected("INVALID_INPUT", "effectiveFromDate", "NOT_ALLOWED"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"), ApprovalOutcome.Rejected("INVALID_INPUT", "effectiveFromMode", "NOT_ALLOWED"),
                ApprovalOutcome.Denied,
            )
            (rowsFor(m) to detailCalls(m)) shouldBe (0L to 0)
        }
        Then("advisory 409 TARGET_NOT_ELIGIBLE_ADVISORY (audited reason): MID suspended, KRW already open, unknown MID; 404 unknown merchant; 503 MASTER down") {
            val operator = base.operator()
            val suspended = base.newMerchantId().also { f.detail(it, accountLifecycle = "SUSPENDED") }
            val open = base.newMerchantId().also { f.detail(it, currencies = f.openKrw(it)) }
            val unknownMid = base.newMerchantId().also { f.detail(it) }
            val unknown = base.newMerchantId()
            val down = base.newMerchantId().also { StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(it), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}")) }
            listOf(
                f.request(operator, suspended), f.request(operator, open), f.request(operator, unknownMid, mid = "mid_01JCZZ00000000000000000ZZ1"), f.request(operator, unknown),
                f.request(operator, down),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"),
                ApprovalOutcome.Rejected("MERCHANT_NOT_FOUND"), ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE"),
            )
            listOf(suspended, open, unknownMid, unknown, down).map(::rowsFor) shouldBe List(5) { 0L }
            listOf(merchantAudits(suspended), merchantAudits(open), merchantAudits(unknownMid)) shouldBe listOf(
                listOf("APPROVAL_DENIED|kind=MERCHANT_CURRENCY_ENABLEMENT;reason=MERCHANT_ACCOUNT_NOT_ACTIVE"),
                listOf("APPROVAL_DENIED|kind=MERCHANT_CURRENCY_ENABLEMENT;reason=CURRENCY_ENABLEMENT_OPEN"),
                listOf("APPROVAL_DENIED|kind=MERCHANT_CURRENCY_ENABLEMENT;reason=MERCHANT_ACCOUNT_NOT_FOUND"),
            )
        }
        Then("an ended KRW record (end in the past) does not block a new request") {
            val m = base.newMerchantId()
            f.detail(m, currencies = StubMasterOpsServer.currencyJson(f.currencyOf(m), effectiveTo = "2026-10-10T15:00:00.000Z"))
            (f.request(base.operator(), m) is ApprovalOutcome.Created) shouldBe true
        }
        Then("C-35: a second currency request for the merchant is 409 DUPLICATE_UNRESOLVED; a contract approval for the same merchant coexists") {
            val (_, m) = f.requested()
            f.request(base.operator(), m) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            merchantAudits(m).last() shouldBe "APPROVAL_DENIED|kind=MERCHANT_CURRENCY_ENABLEMENT;reason=DUPLICATE_UNRESOLVED"
            base.detail(m)
            (base.request(base.operator(), m) is ApprovalOutcome.Created) shouldBe true
            // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.9 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 승인 행은 내부 키 열 `target_merchant_id = ?`로 찾는다(이 시험이 만든 새 행은 모두 내부 키를 가진다)
            root().use { it.column("SELECT unresolved_key FROM admin_approval_request WHERE target_merchant_id = ?", m) }.toSet() shouldBe setOf("MERCHANT:$m", "MERCHANT:CURRENCY:$m")
        }
    }

    Given("MC-2 currency approval (APPROVER + TOTP → TX1 → POST currency-enablements → TX2)") {
        Then("AT_EXECUTION: 200 cur_ → EXECUTED; the 13-field body carries the literal AT_EXECUTION (no effectiveTo key); posted bytes = the TX1-stored bytes") {
            val operator = base.operator()
            val (id, m) = f.requested(operator)
            base.executeReplies[id] = base.succeeded(f.currencyOf(m))
            val approver = base.approver()
            base.approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            val post = posts(id).single()
            post.signatureValid shouldBe true
            val body = json.readTree(post.body)
            body.propertyNames().toList() shouldBe listOf(
                "idempotencyKey", "correlationId", "approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "evidenceRef", "approvalFingerprint", "operation",
                "merchantAccountId", "currency", "effectiveFrom",
            )
            listOf("idempotencyKey", "requestedBy", "approvedBy", "evidenceRef", "operation", "merchantAccountId", "currency", "effectiveFrom").map { body.get(it).asString() } shouldBe listOf(
                "mop1.$id", "admin:${operator.id.value}", "admin:${approver.account.id.value}", MerchantCurrencyApprovalFixture.EVIDENCE, "EnableCurrency", f.midOf(m), "KRW", "AT_EXECUTION",
            )
            // 승인 TX1이 저장한 바이트 = MASTER가 받은 바이트(재생성 없음).
            post.body.contentEquals(storedBody(id)) shouldBe true
            fingerprint(id) shouldBe body.get("approvalFingerprint").asString()
            listOf(status(id), execution(id)) shouldBe listOf("EXECUTED|-", "${f.currencyOf(m)}|-|-")
            audits(id).map { it.substringBefore('|') } shouldBe listOf("APPROVAL_REQUESTED", "APPROVAL_EXECUTING", "APPROVAL_EXECUTED")
        }
        Then("DATE: effectiveFrom/effectiveTo travel as that KST day's 00:00 UTC instants (C-7)") {
            val from = f.tomorrowKst().plusDays(1)
            val to = from.plusDays(10)
            val (id, m) = f.requested(mode = DATE, fromDate = from.toString(), toDate = to.toString())
            base.executeReplies[id] = base.succeeded(f.currencyOf(m))
            base.approve(id) shouldBe ApprovalOutcome.Executed(id)
            val body = json.readTree(posts(id).single().body)
            listOf(body.get("effectiveFrom").asString(), body.get("effectiveTo").asString()) shouldBe
                listOf(MerchantCurrencyApprovalFixture.kstMidnight(from), MerchantCurrencyApprovalFixture.kstMidnight(to))
        }
        Then("no registrant rule (§9.27 — no 1-person registration step): an OPERATOR + APPROVER account other than the requester approves → EXECUTED") {
            val both = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
            val (id, m) = f.requested()
            base.executeReplies[id] = base.succeeded(f.currencyOf(m))
            base.approve(id, both) shouldBe ApprovalOutcome.Executed(id)
        }
        Then("pinned ≠ current (KRW enabled meanwhile, MID suspended, merchant gone) → INVALIDATED(TARGET_CHANGED), no POST; MASTER down → 503, PENDING") {
            val (enabled, m1) = f.requested()
            f.detail(m1, currencies = f.openKrw(m1))
            val (suspended, m2) = f.requested()
            f.detail(m2, accountLifecycle = "SUSPENDED")
            val (gone, m3) = f.requested()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m3), Reply(404, "{\"error\":\"MERCHANT_NOT_FOUND\"}"))
            listOf(base.approve(enabled), base.approve(suspended), base.approve(gone)) shouldBe List(3) { ApprovalOutcome.Invalidated("TARGET_CHANGED") }
            val (down, m4) = f.requested()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m4), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            base.approve(down) shouldBe ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE")
            listOf(status(enabled), status(down)) shouldBe listOf("INVALIDATED|TARGET_CHANGED", "PENDING|-")
            listOf(enabled, suspended, gone, down).sumOf { posts(it).size } shouldBe 0
        }
        Then("MASTER CURRENCY_ENABLEMENT_RETROACTIVE / OVERLAP → OWNER_REJECTED; a success reference outside the cur_ grammar is unreadable → EXECUTION_UNKNOWN (not EXECUTED)") {
            listOf("CURRENCY_ENABLEMENT_RETROACTIVE", "CURRENCY_ENABLEMENT_OVERLAP").forEach { code ->
                val (id, _) = f.requested()
                base.executeReplies[id] = { Reply(200, StubMasterOpsServer.rejected(code)) }
                base.approve(id) shouldBe ApprovalOutcome.OwnerRejected(code)
                status(id) shouldBe "OWNER_REJECTED|$code"
            }
            val (foreign, m) = f.requested()
            base.executeReplies[foreign] = base.succeeded("act_" + f.ulid(m))
            base.approve(foreign) shouldBe ApprovalOutcome.Executing(foreign, "EXECUTION_UNKNOWN")
            status(foreign) shouldBe "EXECUTION_UNKNOWN|-"
        }
    }

    Given("MC-3 recovery kind row: decision lookup → deadline confirmation (operation EnableCurrency in the stored bytes) → resend of the same bytes") {
        Then("lost response: NONE before the deadline resends the same stored bytes (still AT_EXECUTION); DECIDED cur_ (EnableCurrency, same mop1) closes EXECUTED") {
            val (id, m) = lost { f.requested() }
            run(id)
            calls(id) shouldBe "EDE"
            val sent = posts(id).map { it.body }
            sent.size shouldBe 2
            (sent[0].contentEquals(sent[1]) && sent[1].contentEquals(storedBody(id))) shouldBe true
            json.readTree(sent[1]).get("effectiveFrom").asString() shouldBe AT_EXECUTION
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = f.currencyOf(m), operation = "EnableCurrency"))
            run(id)
            listOf(status(id), execution(id), calls(id)) shouldBe listOf("EXECUTED|-", "${f.currencyOf(m)}|-|-", "EDED")
        }
        Then("past the deadline: confirmation only with the stored bytes (operation EnableCurrency); APPROVAL_EXPIRED → OWNER_REJECTED") {
            val (id, _) = lost { f.requested() }
            root().use {
                it.update("UPDATE admin_owner_execution SET approved_at = UTC_TIMESTAMP(3) - INTERVAL 2 HOUR, execute_before = UTC_TIMESTAMP(3) - INTERVAL 10 MINUTE WHERE approval_id = ?", id)
            }
            base.deadlineReplies[id] = { Reply(200, StubMasterOpsServer.rejected("APPROVAL_EXPIRED")) }
            run(id)
            listOf(status(id), calls(id)) shouldBe listOf("OWNER_REJECTED|APPROVAL_EXPIRED", "EDC")
            val confirmed = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.DEADLINE_CONFIRMATIONS).single { base.idOf(it.body) == id }.body
            confirmed.contentEquals(storedBody(id)) shouldBe true
            json.readTree(confirmed).get("operation").asString() shouldBe "EnableCurrency"
        }
        Then("a decision naming another operation (ApprovePaymentMethodActivation) is unreadable for EnableCurrency → stays EXECUTION_UNKNOWN") {
            val (id, m) = lost { f.requested() }
            base.decisionReplies[id] = Reply(200, StubMasterOpsServer.decidedJson(fingerprint(id), reference = f.currencyOf(m), operation = "ApprovePaymentMethodActivation"))
            run(id)
            status(id) shouldBe "EXECUTION_UNKNOWN|-"
        }
    }

    Given("MC-4 views: the approval detail carries the pinned mode; pendingApprovals names the kind") {
        Then("the detail shows pinned effectiveFromMode without the evidence; pending lists MERCHANT_CURRENCY_ENABLEMENT") {
            val requester = base.operator()
            val (id, m) = f.requested(requester)
            val detail = query.detail(requester.id.value, id)!!
            json.readTree(detail.merchant!!.pinnedTarget).get("effectiveFromMode").asString() shouldBe "AT_EXECUTION"
            detail.toString() shouldNotContain MerchantCurrencyApprovalFixture.EVIDENCE
            pending.pendingFor(requester.id.value, m) shouldBe listOf(PendingMerchantApproval(id, "MERCHANT_CURRENCY_ENABLEMENT", "PENDING", null))
            execution(id).shouldBeNull()
        }
    }
})

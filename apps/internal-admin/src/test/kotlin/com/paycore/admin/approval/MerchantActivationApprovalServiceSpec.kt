package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.ApprovalQuery
import com.paycore.admin.approval.application.provided.JustificationResult
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OperatorCancelJustificationQuery
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.merchantops.application.provided.MerchantPendingApprovals
import com.paycore.admin.merchantops.application.provided.PendingMerchantApproval
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.TestTotp
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
 * B3-1 개통 승인·Site 심사 승인 ADMIN 절반(MERCHANT-OPS-002 §8.3 B3-1 8 — §9.16). 실제 MySQL 8.4, MASTER는 실제 HTTP 대역. 명시 Fixture [MerchantActivationApprovalFixture].
 * 행: 근거 필수(400 전 MASTER 미호출), 미리 보기 부적격 409(감사), MASTER 장애 503, 그룹 미해결 키(C-35 — 같은 그룹 409·다른 그룹 공존), pinned `registeredBy` 표시,
 * pinned ≠ 현재 INVALIDATED, 본문(12필드·operation)·소유 참조 대조(C-34).
 * B3-1F(§8.4 B3-1F-4 — D-MOPS-009 ③·D-MOPS-010 ②): 승인자 = pinned `registeredBy`(개통 등록자·Site 등록자)면 TX1에서 TOTP 소비 **전** 403 `APPROVER_IS_REGISTRANT`
 * (PENDING·실행 행 없음·MASTER 미호출·TOTP 불변·감사 `APPROVAL_DENIED reason=APPROVER_IS_REGISTRANT`), MASTER가 새 등록자 거절을 돌려주면 OWNER_REJECTED + 경보.
 * 이전 "Q-MOPS-24 미답 — 등록자도 승인" 고정 시험 2건은 사용자 결정에 따라 반대 기대로 바뀌었다(기대 반전 4건 중 ADMIN 2건).
 * B3-1F review MAJOR-1: TOTP 소비는 TX1에 합류하므로 등록자 Refusal의 rollback만으로도 `last_totp_step`이 되돌아간다 — "TOTP 불변"·n-3 시험은 검사 **순서**와 무관하게
 * 성립하는 불변식이다. 순서(등록자 → TOTP)는 등록자가 틀린 TOTP·이미 쓴 step 코드로 승인해도 `APPROVER_IS_REGISTRANT`(STEP_UP_FAILED 아님, 실패 계수·인증 실패 감사 없음)인 시험이 고정한다.
 */
@SpringBootTest
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantActivationApprovalServiceSpec(
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

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = MerchantActivationApprovalFixture(base, merchants, path, storage)
    f.install()
    afterSpec { f.uninstall() }
    val json = JsonMapper.builder().build()
    fun root() = AdminDbAccounts.root(container)
    /** TOTP 소비 기록(마지막 step)·실패 횟수. 등록자 403 뒤 이 값 불변은 rollback으로도 성립한다 — 순서 증거는 [authFailures]와 틀린/쓴 TOTP 시험(MAJOR-1). */
    fun account(actorId: String) = root().use { it.column("SELECT CONCAT(COALESCE(last_totp_step, -1), '|', failed_attempts) FROM admin_account WHERE actor_id = ?", actorId).single() }
    /** 별도 TX의 STEP_UP 실패 기록(AuthenticationFailureService — rollback되지 않음)이 남기는 인증 실패 감사 행 수. */
    fun authFailures(actorId: String) = root().use {
        (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE actor_id = ? AND action = 'AUTHENTICATION_FAILED'", actorId) as Number).toLong()
    }
    fun alerts(code: String) = registry.find("paycore.admin.ops.integrity_alert").tags("code", code).counters().sumOf { it.count() }
    /** OPERATOR + APPROVER 두 역할 계정(개통·Site를 1인 등록하고 2인 승인도 할 수 있는 사람 — D-MOPS-009 ③ 무효 조건). */
    fun operatorAndApprover() = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.OPERATOR, AdminRole.APPROVER) }
    fun status(id: String) = root().use { it.scalar("SELECT CONCAT(status, '|', COALESCE(result_code, '-')) FROM admin_approval_request WHERE approval_id = ?", id) as String }
    fun execution(id: String) = root().use {
        it.scalar(
            "SELECT CONCAT(COALESCE(owner_reference, '-'), '|', COALESCE(owner_result_code, '-'), '|', COALESCE(integrity_flag, '-')) FROM admin_owner_execution WHERE approval_id = ?", id,
        ) as String?
    }
    fun audits(id: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) }
    // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.9 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 감사 행은 내부 키 가상 열 `target_merchant_key = ?`로 찾는다(MC-3a 전 mer_ 표기 행 포함)
    fun merchantAudits(m: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_merchant_key = ? ORDER BY event_id", m) }
    // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.9 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 승인 행은 `COALESCE(target_merchant_id, target_ref) = ?`로 찾는다
    fun rowsFor(m: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE COALESCE(target_merchant_id, target_ref) = ?", m) as Number).toLong() }
    fun detailCalls(m: String) = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(m)).size
    fun posts(path: String, id: String) = StubMasterOpsServer.requestsForPath(path).filter { base.idOf(it.body) == id }
    fun row(id: String) = root().use {
        it.column(
            "SELECT CONCAT(r.kind, '|', r.status, '|', r.target_kind, '|', r.target_ref, '|', r.target_security_version, '|', r.unresolved_key, '|', c.command_kind, '|', " +
                "c.evidence_ref, '|', (c.pinned_target = r.target_snapshot)) FROM admin_approval_request r JOIN admin_merchant_ops_command c ON c.approval_id = r.approval_id " +
                "WHERE r.approval_id = ?",
            id,
        ).single()
    }
    fun pinned(id: String) = json.readTree(root().use { it.scalar("SELECT pinned_target FROM admin_merchant_ops_command WHERE approval_id = ?", id) as String })

    Given("MA-1 activation approval request (OPERATOR, evidence = institution reply reference required, C-35 group PM)") {
        Then("it stores the MERCHANT approval with MERCHANT:PM:<id>, command PAYMENT_METHOD_ACTIVATION with pinned (status, PRIMARY, registeredBy) and the evidence; one audit without it") {
            val (id, m) = f.requestedActivation()
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            row(id) shouldBe "MERCHANT_PAYMENT_METHOD_ACTIVATION|PENDING|MERCHANT|${MerchantOpsApprovalFixture.DEFAULT_CODE}|0|MERCHANT:PM:$m|PAYMENT_METHOD_ACTIVATION|${MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE}|1"
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §8 MC-3-20·§5.3·§5.6.4 — 가맹점 대상 봉인 5종에 표시용 merchantCode 추가(새 행 필수, 기존 행 읽기 선택 — A 단계 비교 제외 C-19)
            pinned(id).propertyNames().toSet() shouldBe setOf(
                "merchantId", "merchantName", "merchantAccountId", "activationId", "paymentMethod", "status", "primarySiteId", "registeredBy", "merchantCode",
            )
            listOf("merchantAccountId", "activationId", "paymentMethod", "status", "primarySiteId", "registeredBy").map { pinned(id).get(it).asString() } shouldBe
                listOf(f.midOf(m), f.activationOf(m), "CARD", "UNDER_REVIEW", f.primaryOf(m), StubMasterOpsServer.SITE_REGISTRANT)
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|kind=MERCHANT_PAYMENT_METHOD_ACTIVATION")
            root().use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE detail LIKE '%VAN-REPLY%'") as Number).toLong() } shouldBe 0L
            pinned(id).toString() shouldNotContain "VAN-REPLY"
        }
        Then("invalid input is 400 before MASTER (evidence empty/free text/card-number shape, wrong id kinds); a non-OPERATOR is denied without a call") {
            val operator = base.operator()
            val m = base.newMerchantId().also { f.detail(it) }
            listOf(
                f.requestActivation(operator, m, evidence = ""), f.requestActivation(operator, m, evidence = "free text"),
                f.requestActivation(operator, m, evidence = "4111111111111111"), f.requestActivation(operator, m, mid = f.primaryOf(m)),
                f.requestActivation(operator, m, activationId = "cra_" + f.ulid(m)), f.requestActivation(storage.load(base.approver().account.id), m),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"), ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "CARD_NUMBER_PATTERN"), ApprovalOutcome.Rejected("INVALID_INPUT", "merchantAccountId", "FORMAT"),
                ApprovalOutcome.Rejected("INVALID_INPUT", "activationId", "FORMAT"), ApprovalOutcome.Denied,
            )
            (rowsFor(m) to detailCalls(m)) shouldBe (0L to 0)
        }
        Then("advisory 409 TARGET_NOT_ELIGIBLE_ADVISORY (audited reason): activation ACTIVE, MID suspended, no open PRIMARY, unknown activation; 404 unknown merchant; 503 MASTER down") {
            val operator = base.operator()
            val active = base.newMerchantId().also { f.detail(it, activationStatus = "ACTIVE") }
            val suspendedMid = base.newMerchantId().also { f.detail(it, accountLifecycle = "SUSPENDED") }
            val noPrimary = base.newMerchantId().also { f.detail(it, primary = false) }
            val unknownActivation = base.newMerchantId().also { f.detail(it) }
            val unknown = base.newMerchantId()
            val down = base.newMerchantId().also { StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(it), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}")) }
            listOf(
                f.requestActivation(operator, active), f.requestActivation(operator, suspendedMid), f.requestActivation(operator, noPrimary),
                f.requestActivation(operator, unknownActivation, activationId = "act_01JCZZ00000000000000000ZZ1"), f.requestActivation(operator, unknown), f.requestActivation(operator, down),
            ) shouldBe listOf(
                ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"),
                ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY"), ApprovalOutcome.Rejected("MERCHANT_NOT_FOUND"), ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE"),
            )
            listOf(active, suspendedMid, noPrimary, unknownActivation, unknown, down).map(::rowsFor) shouldBe List(6) { 0L }
            listOf(merchantAudits(active), merchantAudits(suspendedMid), merchantAudits(noPrimary), merchantAudits(unknownActivation), merchantAudits(down)) shouldBe listOf(
                listOf("APPROVAL_DENIED|kind=MERCHANT_PAYMENT_METHOD_ACTIVATION;reason=ACTIVATION_NOT_UNDER_REVIEW"),
                listOf("APPROVAL_DENIED|kind=MERCHANT_PAYMENT_METHOD_ACTIVATION;reason=MERCHANT_ACCOUNT_NOT_ACTIVE"),
                listOf("APPROVAL_DENIED|kind=MERCHANT_PAYMENT_METHOD_ACTIVATION;reason=PRIMARY_SITE_NOT_OPEN"),
                listOf("APPROVAL_DENIED|kind=MERCHANT_PAYMENT_METHOD_ACTIVATION;reason=ACTIVATION_NOT_FOUND"), emptyList(),
            )
        }
        Then("C-35: a second activation request for the merchant is 409 DUPLICATE_UNRESOLVED; a site review request and a contract approval for the same merchant coexist") {
            val (_, m) = f.requestedActivation()
            f.requestActivation(base.operator(), m) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
            merchantAudits(m).last() shouldBe "APPROVAL_DENIED|kind=MERCHANT_PAYMENT_METHOD_ACTIVATION;reason=DUPLICATE_UNRESOLVED"
            (f.requestSiteReview(base.operator(), m) is ApprovalOutcome.Created) shouldBe true
            base.detail(m)
            (base.request(base.operator(), m) is ApprovalOutcome.Created) shouldBe true
            rowsFor(m) shouldBe 3L
            // 조회 조건 변경(기대값 그대로): MERCHANT-CODE-001 rev 0.4.9 §5.6.4 — target_ref가 코드 표기가 되어 가맹점 승인 행은 내부 키 열 `target_merchant_id = ?`로 찾는다(이 시험이 만든 새 행은 모두 내부 키를 가진다)
            root().use { it.column("SELECT unresolved_key FROM admin_approval_request WHERE target_merchant_id = ?", m) }.toSet() shouldBe
                setOf("MERCHANT:$m", "MERCHANT:PM:$m", "MERCHANT:SITE_REVIEW:$m")
        }
    }

    Given("MA-2 activation approval (APPROVER + TOTP → TX1 → POST payment-method-activation-approvals → TX2)") {
        Then("200 act_ (= pinned activationId) → EXECUTED: 12-field body with operation, primarySiteId pinned (C-45), key/correlation from the approval id, stored bytes") {
            val operator = base.operator()
            val (id, m) = f.requestedActivation(operator)
            base.executeReplies[id] = base.succeeded(f.activationOf(m))
            val approver = base.approver()
            base.approve(id, approver) shouldBe ApprovalOutcome.Executed(id)
            val post = posts(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS, id).single()
            post.signatureValid shouldBe true
            val body = json.readTree(post.body)
            body.propertyNames().toList() shouldBe listOf(
                "idempotencyKey", "correlationId", "approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "evidenceRef", "approvalFingerprint", "operation",
                "activationId", "primarySiteId",
            )
            listOf("idempotencyKey", "correlationId", "requestedBy", "approvedBy", "evidenceRef", "operation", "activationId", "primarySiteId").map { body.get(it).asString() } shouldBe listOf(
                "mop1.$id", "mop.$id", "admin:${operator.id.value}", "admin:${approver.account.id.value}", MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE,
                "ApprovePaymentMethodActivation", f.activationOf(m), f.primaryOf(m),
            )
            root().use { it.scalar("SELECT fingerprint FROM admin_owner_execution WHERE approval_id = ?", id) } shouldBe body.get("approvalFingerprint").asString()
            listOf(status(id), execution(id)) shouldBe listOf("EXECUTED|-", "${f.activationOf(m)}|-|-")
            audits(id).map { it.substringBefore('|') } shouldBe listOf("APPROVAL_REQUESTED", "APPROVAL_EXECUTING", "APPROVAL_EXECUTED")
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS).count { base.idOf(it.body) == id } shouldBe 0
        }
        Then("D-MOPS-009 ③: the activation registrant (OPERATOR + APPROVER) is refused 403 APPROVER_IS_REGISTRANT before the TOTP — PENDING, no execution row, no POST, TOTP unchanged, APPROVAL_DENIED audit") {
            // 기대 반전(MERCHANT-OPS-002 §8.4 B3-1F-6 — D-MOPS-009 ③ 사용자 결정, 기대 완화 아님): 이전 시험 "Q-MOPS-24 unanswered — current behaviour pinned: the activation
            // registrant (OPERATOR + APPROVER) CAN approve; no APPROVER_IS_REGISTRANT for B3-1 kinds"는 EXECUTED를 기대했다.
            val registrant = operatorAndApprover()
            val m = base.newMerchantId()
            f.detail(m, activationRegistrant = "admin:${registrant.account.id.value}")
            val id = (f.requestActivation(base.operator(), m) as ApprovalOutcome.Created).approvalId
            pinned(id).get("registeredBy").asString() shouldBe "admin:${registrant.account.id.value}"
            base.executeReplies[id] = base.succeeded(f.activationOf(m))
            val before = account(registrant.account.id.value)
            base.approve(id, registrant) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            listOf(status(id), execution(id) ?: "none", posts(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS, id).size.toString(), account(registrant.account.id.value)) shouldBe
                listOf("PENDING|-", "none", "0", before)
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
            // 다른 APPROVER는 같은 승인을 처리할 수 있다.
            base.approve(id) shouldBe ApprovalOutcome.Executed(id)
        }
        Then("D-MOPS-009 ③ (n-3, invariant — holds by order or by the TX1 rollback alike, so it does not pin the order): a refused registrant leaves the TOTP step unconsumed — the same account approves a different request with the same code") {
            // B3-1F review MAJOR-1: TOTP 소비가 TX1(MANDATORY)에 합류하므로 등록자 검사가 TOTP 뒤로 가도 Refusal rollback이 소비를 되돌려 이 시험은 통과한다.
            // 검사 순서(등록자 → TOTP)는 아래 "wrong / already-used TOTP" 시험이 고정한다. 기대값은 그대로다.
            val registrant = operatorAndApprover()
            val own = base.newMerchantId().also { f.detail(it, activationRegistrant = "admin:${registrant.account.id.value}") }
            val ownId = (f.requestActivation(base.operator(), own) as ApprovalOutcome.Created).approvalId
            val (otherId, other) = f.requestedActivation()
            base.executeReplies[otherId] = base.succeeded(f.activationOf(other))
            val code = TestTotp.code(registrant.secret)
            val before = account(registrant.account.id.value)
            base.approve(ownId, registrant, totp = code) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            account(registrant.account.id.value) shouldBe before
            // TOTP는 계정별이다 — 거절된 시도가 이 코드를 쓰지 않았으므로 같은 계정이 같은 코드로 등록자가 아닌 다른 건을 승인할 수 있다.
            base.approve(otherId, registrant, totp = code) shouldBe ApprovalOutcome.Executed(otherId)
            status(ownId) shouldBe "PENDING|-"
        }
        Then("D-MOPS-009 ③ order (MAJOR-1): the activation registrant with a WRONG TOTP or an ALREADY-USED step code is still refused APPROVER_IS_REGISTRANT — not STEP_UP_FAILED; no failure counted, no AUTHENTICATION_FAILED audit") {
            val registrant = operatorAndApprover()
            val actor = registrant.account.id.value
            fun ownRequest() = base.newMerchantId().let { m ->
                f.detail(m, activationRegistrant = "admin:$actor")
                (f.requestActivation(base.operator(), m) as ApprovalOutcome.Created).approvalId to m
            }
            // (1) 틀린 TOTP: 등록자 판정이 TOTP 판정 앞이면 403 APPROVER_IS_REGISTRANT, 뒤면 STEP_UP_FAILED + 별도 TX 실패 계수·감사(rollback되지 않음).
            val (wrongId, wrongM) = ownRequest()
            base.executeReplies[wrongId] = base.succeeded(f.activationOf(wrongM))
            val before = account(actor)
            val failuresBefore = authFailures(actor)
            base.approve(wrongId, registrant, totp = TestTotp.wrongCode(registrant.secret)) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            listOf(status(wrongId), execution(wrongId) ?: "none", posts(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS, wrongId).size.toString(), account(actor)) shouldBe
                listOf("PENDING|-", "none", "0", before)
            authFailures(actor) shouldBe failuresBefore
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(wrongId).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
            // (2) 이미 쓴 step 코드: 같은 코드로 남의 개통을 먼저 승인(그 step 소비)한 뒤 자기 개통에 같은 코드 → 여전히 APPROVER_IS_REGISTRANT.
            val (otherId, other) = f.requestedActivation()
            base.executeReplies[otherId] = base.succeeded(f.activationOf(other))
            val code = TestTotp.code(registrant.secret)
            base.approve(otherId, registrant, totp = code) shouldBe ApprovalOutcome.Executed(otherId)
            val (usedId, usedM) = ownRequest()
            val consumed = account(actor)
            base.approve(usedId, registrant, totp = code) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            listOf(status(usedId), execution(usedId) ?: "none", account(actor), authFailures(actor).toString()) shouldBe
                listOf("PENDING|-", "none", consumed, failuresBefore.toString())
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(usedId).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
        }
        Then("MASTER returning the new registrant rejections (ADMIN check bypassed) → OWNER_REJECTED + integrity alert (DEFECT_REJECTIONS)") {
            listOf("APPROVER_IS_ACTIVATION_REGISTRANT", "APPROVER_IS_SITE_REGISTRANT").forEach { code ->
                val (id, _) = if (code == "APPROVER_IS_ACTIVATION_REGISTRANT") f.requestedActivation() else f.requestedSiteReview()
                base.executeReplies[id] = { Reply(200, StubMasterOpsServer.rejected(code)) }
                val before = alerts(code)
                base.approve(id) shouldBe ApprovalOutcome.OwnerRejected(code)
                listOf(status(id), (alerts(code) - before).toString()) shouldBe listOf("OWNER_REJECTED|$code", "1.0")
            }
        }
        Then("pinned ≠ current (PRIMARY changed, activation became ACTIVE, registrant changed, merchant gone) → INVALIDATED(TARGET_CHANGED), no POST; MASTER down → 503, PENDING") {
            val (primaryMoved, m1) = f.requestedActivation()
            f.detail(m1, primarySite = f.secondSiteOf(m1))
            val (activated, m2) = f.requestedActivation()
            f.detail(m2, activationStatus = "ACTIVE")
            val (registrantChanged, m3) = f.requestedActivation()
            f.detail(m3, activationRegistrant = "admin:adm_01JCOT0000000000000000OT01")
            val (gone, m4) = f.requestedActivation()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m4), Reply(404, "{\"error\":\"MERCHANT_NOT_FOUND\"}"))
            listOf(base.approve(primaryMoved), base.approve(activated), base.approve(registrantChanged), base.approve(gone)) shouldBe List(4) { ApprovalOutcome.Invalidated("TARGET_CHANGED") }
            val (down, m5) = f.requestedActivation()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(m5), Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            base.approve(down) shouldBe ApprovalOutcome.Unavailable("MASTER_UNAVAILABLE")
            listOf(status(primaryMoved), status(down)) shouldBe listOf("INVALIDATED|TARGET_CHANGED", "PENDING|-")
            listOf(primaryMoved, activated, registrantChanged, gone, down).sumOf { posts(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS, it).size } shouldBe 0
        }
        Then("REJECTED PRIMARY_SITE_MISMATCH → OWNER_REJECTED; a foreign act_ (C-34) → EXECUTION_UNKNOWN + REFERENCE_MISMATCH (not EXECUTED)") {
            val (rejected, _) = f.requestedActivation()
            base.executeReplies[rejected] = { Reply(200, StubMasterOpsServer.rejected("PRIMARY_SITE_MISMATCH")) }
            base.approve(rejected) shouldBe ApprovalOutcome.OwnerRejected("PRIMARY_SITE_MISMATCH")
            val (foreign, _) = f.requestedActivation()
            base.executeReplies[foreign] = base.succeeded("act_01JCZZ00000000000000000ZZ1")
            base.approve(foreign) shouldBe ApprovalOutcome.Executing(foreign, "EXECUTION_UNKNOWN")
            listOf(status(rejected), status(foreign), execution(foreign)!!.split('|')[2]) shouldBe
                listOf("OWNER_REJECTED|PRIMARY_SITE_MISMATCH", "EXECUTION_UNKNOWN|-", "REFERENCE_MISMATCH")
        }
    }

    Given("MS-1 site review approval request (evidence = PG review basis, reason REVIEW_PASSED only, C-35 group SITE_REVIEW)") {
        Then("it stores MERCHANT:SITE_REVIEW:<id>, command SITE_REVIEW_APPROVAL with pinned (channel, review status PENDING, reason, registeredBy) — no channel identifier, no evidence") {
            val (id, m) = f.requestedSiteReview()
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            row(id) shouldBe "MERCHANT_SITE_REVIEW_APPROVAL|PENDING|MERCHANT|${MerchantOpsApprovalFixture.DEFAULT_CODE}|0|MERCHANT:SITE_REVIEW:$m|SITE_REVIEW_APPROVAL|${MerchantActivationApprovalFixture.SITE_EVIDENCE}|1"
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §8 MC-3-20·§5.3·§5.6.4 — 가맹점 대상 봉인 5종에 표시용 merchantCode 추가(새 행 필수, 기존 행 읽기 선택 — A 단계 비교 제외 C-19)
            pinned(id).propertyNames().toSet() shouldBe setOf("merchantId", "merchantName", "merchantSiteId", "channelType", "reviewStatus", "reasonCode", "registeredBy", "merchantCode")
            listOf("merchantSiteId", "channelType", "reviewStatus", "reasonCode", "registeredBy").map { pinned(id).get(it).asString() } shouldBe
                listOf(f.secondSiteOf(m), "WEB", "PENDING", "REVIEW_PASSED", StubMasterOpsServer.SITE_REGISTRANT)
            pinned(id).toString() shouldNotContain "second.example.test"
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|kind=MERCHANT_SITE_REVIEW_APPROVAL")
        }
        Then("a reject code is 400 reasonCode NOT_ALLOWED (C-40 — rejection is the 1-person channel); missing evidence 400; advisory 409 for APPROVED, REJECTED, inactive and unknown sites; SUSPENDED is eligible") {
            val operator = base.operator()
            val m = base.newMerchantId().also { f.detail(it) }
            listOf(f.requestSiteReview(operator, m, reasonCode = "DOCUMENT_INSUFFICIENT"), f.requestSiteReview(operator, m, evidence = "")) shouldBe listOf(
                ApprovalOutcome.Rejected("INVALID_INPUT", "reasonCode", "NOT_ALLOWED"), ApprovalOutcome.Rejected("INVALID_INPUT", "evidenceRef", "FORMAT"),
            )
            val approved = base.newMerchantId().also { f.detail(it, secondReview = "APPROVED") }
            val rejected = base.newMerchantId().also { f.detail(it, secondReview = "REJECTED") }
            val inactive = base.newMerchantId().also { f.detail(it, secondLifecycle = "TERMINATED") }
            val suspended = base.newMerchantId().also { f.detail(it, secondReview = "SUSPENDED") }
            listOf(
                f.requestSiteReview(operator, approved), f.requestSiteReview(operator, rejected), f.requestSiteReview(operator, inactive),
                f.requestSiteReview(operator, m, siteId = "site_01JCZZ00000000000000000ZZ1"),
            ) shouldBe List(4) { ApprovalOutcome.Rejected("TARGET_NOT_ELIGIBLE_ADVISORY") }
            listOf(merchantAudits(approved), merchantAudits(rejected), merchantAudits(inactive)).map { it.single().substringAfter("reason=") } shouldBe
                listOf("SITE_REVIEW_NOT_OPEN", "SITE_REVIEW_NOT_OPEN", "SITE_NOT_ACTIVE")
            (f.requestSiteReview(operator, suspended) is ApprovalOutcome.Created) shouldBe true
            rowsFor(m) shouldBe 0L
        }
        Then("a second site review request for the merchant (another site) is 409 DUPLICATE_UNRESOLVED (same group)") {
            val (_, m) = f.requestedSiteReview()
            f.requestSiteReview(base.operator(), m, siteId = f.primaryOf(m)) shouldBe ApprovalOutcome.Rejected("DUPLICATE_UNRESOLVED")
        }
    }

    Given("MS-2 site review approval → POST site-review-approvals") {
        Then("site_…#n of the pinned site → EXECUTED; body has operation DecideSiteReview, merchantSiteId, reasonCode; another site's decision → REFERENCE_MISMATCH") {
            val (id, m) = f.requestedSiteReview()
            base.executeReplies[id] = base.succeeded(f.secondSiteOf(m) + "#1")
            base.approve(id) shouldBe ApprovalOutcome.Executed(id)
            val body = json.readTree(posts(StubMasterOpsServer.SITE_REVIEW_APPROVALS, id).single().body)
            body.propertyNames().toList().takeLast(3) shouldBe listOf("operation", "merchantSiteId", "reasonCode")
            listOf(body.get("operation").asString(), body.get("merchantSiteId").asString(), body.get("reasonCode").asString(), body.get("evidenceRef").asString()) shouldBe
                listOf("DecideSiteReview", f.secondSiteOf(m), "REVIEW_PASSED", MerchantActivationApprovalFixture.SITE_EVIDENCE)
            listOf(status(id), execution(id)) shouldBe listOf("EXECUTED|-", "${f.secondSiteOf(m)}#1|-|-")
            val (foreign, m2) = f.requestedSiteReview()
            base.executeReplies[foreign] = base.succeeded(f.primaryOf(m2) + "#1")
            base.approve(foreign) shouldBe ApprovalOutcome.Executing(foreign, "EXECUTION_UNKNOWN")
            execution(foreign)!!.split('|')[2] shouldBe "REFERENCE_MISMATCH"
        }
        Then("D-MOPS-009 ③ / D-MOPS-010 ②: the site registrant (OPERATOR + APPROVER) is refused 403 APPROVER_IS_REGISTRANT for PENDING and SUSPENDED sites (same matrix)") {
            // 기대 반전(MERCHANT-OPS-002 §8.4 B3-1F-6 — D-MOPS-009 ③): 이전 시험 "Q-MOPS-24 unanswered: the site registrant may approve the site review (no registrant check);
            // review approved meanwhile → INVALIDATED"의 등록자 부분은 EXECUTED를 기대했다. INVALIDATED 부분은 아래 시험으로 옮겨 그대로 유지한다.
            val registrant = operatorAndApprover()
            listOf(null, "SUSPENDED").forEach { review ->
                val m = base.newMerchantId()
                f.detail(m, siteRegistrant = "admin:${registrant.account.id.value}", secondReview = review)
                val id = (f.requestSiteReview(base.operator(), m) as ApprovalOutcome.Created).approvalId
                base.executeReplies[id] = base.succeeded(f.secondSiteOf(m) + "#1")
                val before = account(registrant.account.id.value)
                base.approve(id, registrant) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
                listOf(status(id), execution(id) ?: "none", posts(StubMasterOpsServer.SITE_REVIEW_APPROVALS, id).size.toString(), account(registrant.account.id.value)) shouldBe
                    listOf("PENDING|-", "none", "0", before)
                // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
                audits(id).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
                base.approve(id) shouldBe ApprovalOutcome.Executed(id)
            }
        }
        Then("D-MOPS-009 ③ / D-MOPS-010 ② order (MAJOR-1): the site registrant with a WRONG TOTP (PENDING and SUSPENDED) or an ALREADY-USED step code is still refused APPROVER_IS_REGISTRANT — not STEP_UP_FAILED; no failure counted or audited") {
            val registrant = operatorAndApprover()
            val actor = registrant.account.id.value
            fun ownRequest(review: String?) = base.newMerchantId().let { m ->
                f.detail(m, siteRegistrant = "admin:$actor", secondReview = review)
                (f.requestSiteReview(base.operator(), m) as ApprovalOutcome.Created).approvalId to m
            }
            val failuresBefore = authFailures(actor)
            listOf(null, "SUSPENDED").forEach { review ->
                val (id, m) = ownRequest(review)
                base.executeReplies[id] = base.succeeded(f.secondSiteOf(m) + "#1")
                val before = account(actor)
                base.approve(id, registrant, totp = TestTotp.wrongCode(registrant.secret)) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
                listOf(status(id), execution(id) ?: "none", posts(StubMasterOpsServer.SITE_REVIEW_APPROVALS, id).size.toString(), account(actor)) shouldBe
                    listOf("PENDING|-", "none", "0", before)
                // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
                audits(id).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
            }
            authFailures(actor) shouldBe failuresBefore
            val (otherId, other) = f.requestedSiteReview()
            base.executeReplies[otherId] = base.succeeded(f.secondSiteOf(other) + "#1")
            val code = TestTotp.code(registrant.secret)
            base.approve(otherId, registrant, totp = code) shouldBe ApprovalOutcome.Executed(otherId)
            val (usedId, usedM) = ownRequest(null)
            val consumed = account(actor)
            base.approve(usedId, registrant, totp = code) shouldBe ApprovalOutcome.Refused("APPROVER_IS_REGISTRANT")
            listOf(status(usedId), account(actor), authFailures(actor).toString()) shouldBe listOf("PENDING|-", consumed, failuresBefore.toString())
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.8 §5.1·§5.6.4 C-32(Q-MC-4 — D-MCODE-001) — MC-3a부터 가맹점 대상 새 승인 행·그 감사 행의 target_ref = 요청 시점 가맹점 코드(내부 키는 target_merchant_id = mer_)
            audits(usedId).last() shouldBe "APPROVAL_DENIED|${MerchantOpsApprovalFixture.DEFAULT_CODE}|reason=APPROVER_IS_REGISTRANT"
        }
        Then("another approver: the site review approved meanwhile → INVALIDATED (kept from the former Q-MOPS-24 test)") {
            val (changed, m2) = f.requestedSiteReview()
            f.detail(m2, secondReview = "APPROVED")
            base.approve(changed) shouldBe ApprovalOutcome.Invalidated("TARGET_CHANGED")
        }
    }

    Given("MA-3 views: evidence only via the authorized lookup; pinned registeredBy in the detail; pendingApprovals names the kind") {
        Then("requester and APPROVER read the evidence; the detail carries pinned registeredBy without the evidence; pending lists both B3 kinds") {
            val requester = base.operator()
            val (id, m) = f.requestedActivation(requester)
            (justifications.view(requester.id.value, id, path.context()) as JustificationResult.EvidenceFound).evidenceRef shouldBe MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE
            val detail = query.detail(requester.id.value, id)!!
            json.readTree(detail.merchant!!.pinnedTarget).get("registeredBy").asString() shouldBe StubMasterOpsServer.SITE_REGISTRANT
            detail.toString() shouldNotContain MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE
            val site = (f.requestSiteReview(requester, m) as ApprovalOutcome.Created).approvalId
            pending.pendingFor(requester.id.value, m).toSet() shouldBe setOf(
                PendingMerchantApproval(id, "MERCHANT_PAYMENT_METHOD_ACTIVATION", "PENDING", null), PendingMerchantApproval(site, "MERCHANT_SITE_REVIEW_APPROVAL", "PENDING", null),
            )
        }
    }
})

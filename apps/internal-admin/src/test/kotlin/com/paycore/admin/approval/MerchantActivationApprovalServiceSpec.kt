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
import com.paycore.admin.support.column
import com.paycore.admin.support.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * B3-1 개통 승인·Site 심사 승인 ADMIN 절반(MERCHANT-OPS-002 §8.3 B3-1 8 — §9.16). 실제 MySQL 8.4, MASTER는 실제 HTTP 대역. 명시 Fixture [MerchantActivationApprovalFixture].
 * 행: 근거 필수(400 전 MASTER 미호출), 미리 보기 부적격 409(감사), MASTER 장애 503, 그룹 미해결 키(C-35 — 같은 그룹 409·다른 그룹 공존), pinned `registeredBy` 표시,
 * **등록자 검사 없음(Q-MOPS-24 미답 — 현재 동작 고정)**, pinned ≠ 현재 INVALIDATED, 본문(12필드·operation)·소유 참조 대조(C-34).
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
    private val container: MySQLContainer,
) : BehaviorSpec({

    val base = MerchantOpsApprovalFixture(merchants, owners, path, ready, storage)
    val f = MerchantActivationApprovalFixture(base, merchants, path, storage)
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
    fun audits(id: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(target_ref, '-'), '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE approval_id = ? ORDER BY event_id", id) }
    fun merchantAudits(m: String) = root().use { it.column("SELECT CONCAT(action, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE target_ref = ? ORDER BY event_id", m) }
    fun rowsFor(m: String) = root().use { (it.scalar("SELECT COUNT(*) FROM admin_approval_request WHERE target_ref = ?", m) as Number).toLong() }
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
            row(id) shouldBe "MERCHANT_PAYMENT_METHOD_ACTIVATION|PENDING|MERCHANT|$m|0|MERCHANT:PM:$m|PAYMENT_METHOD_ACTIVATION|${MerchantActivationApprovalFixture.ACTIVATION_EVIDENCE}|1"
            pinned(id).propertyNames().toSet() shouldBe setOf("merchantId", "merchantName", "merchantAccountId", "activationId", "paymentMethod", "status", "primarySiteId", "registeredBy")
            listOf("merchantAccountId", "activationId", "paymentMethod", "status", "primarySiteId", "registeredBy").map { pinned(id).get(it).asString() } shouldBe
                listOf(f.midOf(m), f.activationOf(m), "CARD", "UNDER_REVIEW", f.primaryOf(m), StubMasterOpsServer.SITE_REGISTRANT)
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|$m|kind=MERCHANT_PAYMENT_METHOD_ACTIVATION")
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
            root().use { it.column("SELECT unresolved_key FROM admin_approval_request WHERE target_ref = ?", m) }.toSet() shouldBe
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
        Then("Q-MOPS-24 unanswered — current behaviour pinned: the activation registrant (OPERATOR + APPROVER) CAN approve; no APPROVER_IS_REGISTRANT for B3-1 kinds") {
            val registrant = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.APPROVER) }
            val m = base.newMerchantId()
            f.detail(m, activationRegistrant = "admin:${registrant.account.id.value}")
            val id = (f.requestActivation(base.operator(), m) as ApprovalOutcome.Created).approvalId
            pinned(id).get("registeredBy").asString() shouldBe "admin:${registrant.account.id.value}"
            base.executeReplies[id] = base.succeeded(f.activationOf(m))
            base.approve(id, registrant) shouldBe ApprovalOutcome.Executed(id)
            audits(id).none { it.contains("APPROVER_IS_REGISTRANT") } shouldBe true
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
            row(id) shouldBe "MERCHANT_SITE_REVIEW_APPROVAL|PENDING|MERCHANT|$m|0|MERCHANT:SITE_REVIEW:$m|SITE_REVIEW_APPROVAL|${MerchantActivationApprovalFixture.SITE_EVIDENCE}|1"
            pinned(id).propertyNames().toSet() shouldBe setOf("merchantId", "merchantName", "merchantSiteId", "channelType", "reviewStatus", "reasonCode", "registeredBy")
            listOf("merchantSiteId", "channelType", "reviewStatus", "reasonCode", "registeredBy").map { pinned(id).get(it).asString() } shouldBe
                listOf(f.secondSiteOf(m), "WEB", "PENDING", "REVIEW_PASSED", StubMasterOpsServer.SITE_REGISTRANT)
            pinned(id).toString() shouldNotContain "second.example.test"
            audits(id) shouldBe listOf("APPROVAL_REQUESTED|$m|kind=MERCHANT_SITE_REVIEW_APPROVAL")
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
        Then("Q-MOPS-24 unanswered: the site registrant may approve the site review (no registrant check); review approved meanwhile → INVALIDATED") {
            val registrant = ready.withoutRoles().also { path.grant(it.account.id, AdminRole.APPROVER) }
            val m = base.newMerchantId()
            f.detail(m, siteRegistrant = "admin:${registrant.account.id.value}")
            val id = (f.requestSiteReview(base.operator(), m) as ApprovalOutcome.Created).approvalId
            base.executeReplies[id] = base.succeeded(f.secondSiteOf(m) + "#1")
            base.approve(id, registrant) shouldBe ApprovalOutcome.Executed(id)
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

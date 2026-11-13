package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.ApprovedCommandFixture.activationApproval
import com.paycore.master.merchant.application.ApprovedCommandFixture.evidenceRow
import com.paycore.master.merchant.application.ApprovedCommandFixture.siteReviewApproval
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuedWithActivation
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApprovalDecisionUseCase
import com.paycore.master.merchant.application.provided.ApprovePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.DecideSiteReviewCommand
import com.paycore.master.merchant.application.provided.DeadlineConfirmationResult
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.RejectSiteReviewCommand
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.MasterFixture
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteReviewStatus
import com.paycore.master.scalar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer

/**
 * MERCHANT-OPS-002 §8.3 B3-1-2·3(실제 MySQL rollback): 근거 있는 개통 승인·Site 심사 승인의 한 로컬 TX(업무 효과·PRIMARY 확인·권리 revision·Snapshot·이력·Outbox·`admin_request`·근거 행),
 * Domain 기관 근거 = 승인 근거(C-36), 고정 PRIMARY 불일치 `PRIMARY_SITE_MISMATCH`(C-45), 1인 거절(`RejectSiteReview` — REJECTED 고정, nit-1·C-40), 근거 없는 2인 메서드의 seed 관문(C-40),
 * 기한 확인이 B3 operation을 업무 본문 없이 종결, 불변식 n-a.
 * B3-1F(D-MOPS-009 ③·D-MOPS-010 ② — §9.27·§8.4 B3-1F-2): 근거 경로의 등록자 = 승인자는 저장된 거절(`APPROVER_IS_ACTIVATION_REGISTRANT`·`APPROVER_IS_SITE_REGISTRANT`, 근거 REJECTED 행)이고
 * 업무 행·PRIMARY 확인·revision·Snapshot·이력·**Outbox** 불변(m-4 — 행 수 집계에 Outbox 열). 이전의 "Q-MOPS-24 답 전 — 등록자도 통과" 고정 시험은 사용자 결정에 따라 반대 기대로 바뀌었다
 * (기대 반전 4건 중 MASTER 1건).
 */
@MasterDbTest
class ActivationApprovalAtomicitySpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val decisions: ApprovalDecisionUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val faults: MasterFaultInjector,
    private val mode: SwitchableDeploymentMode,
) : BehaviorSpec({

    afterEach {
        clock.reset()
        faults.reset()
        mode.reset()
    }

    class Injected : RuntimeException("injected")

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { it.scalar(sql, *args)?.toString() }
    fun evidence(approvalId: String) = runtime(container).use { evidenceRow(it, approvalId) }

    data class Rows(
        val status: String?, val activationEvents: Long, val confirmations: Long, val entitlement: Long, val snapshots: Long, val history: Long,
        val adminRequests: Long, val evidence: Long, val outbox: Long,
    )

    /** m-4(B3-1F): 이 개통·그 PRIMARY 확인·그 가맹점 Snapshot에 딸린 Outbox 행 수(통합 이벤트 + `ENTITLEMENT_SNAPSHOT`). */
    fun activationOutbox(ready: OnboardingFixture.ReadyMerchant, activation: PaymentMethodActivationId): Long = count(
        "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? OR aggregate_id IN (SELECT confirmation_id FROM site_activation_confirmation WHERE activation_id = ?) " +
            "OR event_id IN (SELECT outbox_event_id FROM entitlement_snapshot_issue WHERE merchant_id = ?)",
        activation.value, activation.value, ready.merchantId.value,
    )

    fun rows(ready: OnboardingFixture.ReadyMerchant, activation: PaymentMethodActivationId, approvalId: String) = Rows(
        status = text("SELECT status FROM payment_method_activation WHERE activation_id = ?", activation.value),
        activationEvents = count("SELECT COUNT(*) FROM payment_method_activation_event WHERE activation_id = ?", activation.value),
        confirmations = count("SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?", activation.value),
        entitlement = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", ready.merchantId.value),
        snapshots = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", ready.merchantId.value),
        history = count("SELECT COUNT(*) FROM master_change_history WHERE change_type = 'PaymentMethodActivationApproved' AND aggregate_id = ?", activation.value),
        adminRequests = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", "mop1.$approvalId"),
        evidence = count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", approvalId),
        outbox = activationOutbox(ready, activation),
    )

    fun issued(activationRegistrant: ActorRef = OnboardingFixture.operator): Triple<OnboardingFixture.ReadyMerchant, MerchantAccountId, PaymentMethodActivationId> {
        val (ready, ids) = issuedWithActivation(onboarding, activationRegistrant)
        return Triple(ready, ids.first, ids.second)
    }

    /** B3-1F: 승인자(`ApprovalEvidenceFixtures.APPROVER`)가 1인 경로로 등록한 Site(같은 가맹점의 셋째 Site — 심사 결정 없음). */
    fun siteRegisteredByApprover(ready: OnboardingFixture.ReadyMerchant): MerchantSiteId = MerchantSiteId(
        succeeded(
            onboarding.registerMerchantSite(
                RegisterMerchantSiteCommand(request(actor = ApprovalEvidenceFixtures.APPROVER), ready.merchantId, ChannelType.WEB, OnboardingFixture.host()),
            ),
        ),
    )

    Given("B3-1-2: an evidence-backed payment method activation approval is one local transaction") {
        Then("success: ACTIVE with the approval evidence as institution evidence (C-36), one PRIMARY confirmation, revision +1, Snapshot, history (actor = approver), EXECUTED evidence row") {
            val (ready, _, activation) = issued()
            val command = activationApproval(activation, ready.siteId)
            val before = rows(ready, activation, command.evidence.approvalId)
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe AdminCommandResult.Succeeded(activation.value, replayed = false)
            rows(ready, activation, command.evidence.approvalId) shouldBe before.copy(
                status = "ACTIVE", activationEvents = before.activationEvents + 1, confirmations = 1, entitlement = before.entitlement + 1,
                snapshots = before.snapshots + 1, history = before.history + 1, adminRequests = 1, evidence = 1,
                // m-4(B3-1F): Outbox = 개통 전이 1 + PRIMARY 확인 1 + Snapshot 1.
                outbox = before.outbox + 3,
            )
            text("SELECT evidence_ref FROM payment_method_activation WHERE activation_id = ?", activation.value) shouldBe ApprovedCommandFixture.INSTITUTION_REPLY
            text("SELECT merchant_site_id FROM site_activation_confirmation WHERE activation_id = ?", activation.value) shouldBe ready.siteId.value
            text("SELECT evidence_ref FROM site_activation_confirmation WHERE activation_id = ?", activation.value) shouldBe ApprovedCommandFixture.INSTITUTION_REPLY
            text(
                "SELECT actor_ref FROM master_change_history WHERE change_type = 'PaymentMethodActivationApproved' AND aggregate_id = ? ORDER BY id DESC LIMIT 1", activation.value,
            ) shouldBe ApprovalEvidenceFixtures.APPROVER.value
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.operation, it.outcome, it.resultReference, it.decidedVia, it.evidenceRef) shouldBe
                    listOf("ApprovePaymentMethodActivation", "EXECUTED", activation.value, "EXECUTE", ApprovedCommandFixture.INSTITUTION_REPLY)
            }
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe AdminCommandResult.Succeeded(activation.value, replayed = true)
        }

        Then("a fault right after the evidence row write, or at the result write, rolls back everything; a retry then executes once") {
            listOf(MasterFaultPoint.APPROVAL_EVIDENCE_RECORD, MasterFaultPoint.ADMIN_COMPLETE, MasterFaultPoint.CONFIRMATION_INSERT).forEach { point ->
                withClue(point) {
                    val (ready, _, activation) = issued()
                    val command = activationApproval(activation, ready.siteId)
                    val before = rows(ready, activation, command.evidence.approvalId)
                    faults.after(point) { throw Injected() }
                    shouldThrow<Injected> { entitlement.approvePaymentMethodActivationWithEvidence(command) }
                    rows(ready, activation, command.evidence.approvalId) shouldBe before
                    faults.reset()
                    entitlement.approvePaymentMethodActivationWithEvidence(command).shouldBeInstanceOf<AdminCommandResult.Succeeded>().replayed shouldBe false
                    rows(ready, activation, command.evidence.approvalId).evidence shouldBe 1
                }
            }
        }

        Then("the same actor is a stored APPROVAL_SAME_ACTOR (REJECTED evidence row), the activation stays UNDER_REVIEW") {
            val (ready, _, activation) = issued()
            val command = activationApproval(activation, ready.siteId, ApprovalEvidenceFixtures.sameActor())
            val before = rows(ready, activation, command.evidence.approvalId)
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_SAME_ACTOR, null, replayed = false)
            rows(ready, activation, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "APPROVAL_SAME_ACTOR"
        }

        Then("C-45: a fixed primarySiteId that is not the open PRIMARY is a stored PRIMARY_SITE_MISMATCH; nothing is confirmed") {
            val (ready, _, activation) = issued()
            val command = activationApproval(activation, ready.secondSiteId)
            val before = rows(ready, activation, command.evidence.approvalId)
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.PRIMARY_SITE_MISMATCH, null, replayed = false)
            rows(ready, activation, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().let { listOf(it.outcome, it.resultCode) shouldBe listOf("REJECTED", "PRIMARY_SITE_MISMATCH") }
        }

        Then("order (m-4): an expired evidence for an unknown activation is ACTIVATION_NOT_FOUND (pre-lock lookup); for a known one it is APPROVAL_EXPIRED") {
            val missing = PaymentMethodActivationId("act_" + MasterFixture.ulid())
            val (ready, _, activation) = issued()
            val unknown = activationApproval(missing, ready.siteId, ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            entitlement.approvePaymentMethodActivationWithEvidence(unknown) shouldBe AdminCommandResult.Rejected(MasterRejection.ACTIVATION_NOT_FOUND, null, replayed = false)
            val known = activationApproval(activation, ready.siteId, ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            entitlement.approvePaymentMethodActivationWithEvidence(known) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false)
            text("SELECT status FROM payment_method_activation WHERE activation_id = ?", activation.value) shouldBe "UNDER_REVIEW"
        }

        Then("D-MOPS-009 ③: the activation registrant's evidence approval is a stored APPROVER_IS_ACTIVATION_REGISTRANT rejection — nothing else changes; the same bytes replay") {
            // 기대 반전(MERCHANT-OPS-002 §8.4 B3-1F-6 — D-MOPS-009 ③ 사용자 결정, 기대 완화 아님): 이전 시험 "Q-MOPS-24 not answered yet: the activation registrant may approve
            // (no registrant check implemented — current state pinned by this test)"는 Succeeded를 기대했다.
            val (ready, _, activation) = issued(activationRegistrant = ApprovalEvidenceFixtures.APPROVER)
            val command = activationApproval(activation, ready.siteId)
            val before = rows(ready, activation, command.evidence.approvalId)
            before.status shouldBe "UNDER_REVIEW"
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe
                AdminCommandResult.Rejected(MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT, null, replayed = false)
            // 상태·전이 이력·PRIMARY 확인·revision·Snapshot·변경 이력·Outbox 불변, 요청 결과 1 + 근거 REJECTED 행 1(같은 TX).
            rows(ready, activation, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.operation, it.outcome, it.resultCode, it.decidedVia, it.resultReference) shouldBe
                    listOf("ApprovePaymentMethodActivation", "REJECTED", "APPROVER_IS_ACTIVATION_REGISTRANT", "EXECUTE", null)
            }
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe
                AdminCommandResult.Rejected(MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT, null, replayed = true)
            rows(ready, activation, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
        }

        Then("D-MOPS-009 ③ order (m-4): for the registrant's activation an unknown id is ACTIVATION_NOT_FOUND and expired evidence is APPROVAL_EXPIRED — both before the registrant check") {
            val (ready, _, activation) = issued(activationRegistrant = ApprovalEvidenceFixtures.APPROVER)
            val missing = PaymentMethodActivationId("act_" + MasterFixture.ulid())
            entitlement.approvePaymentMethodActivationWithEvidence(activationApproval(missing, ready.siteId)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.ACTIVATION_NOT_FOUND, null, replayed = false)
            val expired = activationApproval(activation, ready.siteId, ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            entitlement.approvePaymentMethodActivationWithEvidence(expired) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false)
            text("SELECT status FROM payment_method_activation WHERE activation_id = ?", activation.value) shouldBe "UNDER_REVIEW"
        }

        Then("D-MOPS-009 ③: the deadline confirmation never runs the body — the registrant's approval after the deadline is APPROVAL_EXPIRED (DEADLINE_CONFIRM), then replayed") {
            val (ready, _, activation) = issued(activationRegistrant = ApprovalEvidenceFixtures.APPROVER)
            val command = activationApproval(activation, ready.siteId)
            val before = rows(ready, activation, command.evidence.approvalId)
            clock.set(command.evidence.executeBefore)
            decisions.confirmDeadline(command) shouldBe
                DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false))
            evidence(command.evidence.approvalId).shouldNotBeNull().let { listOf(it.resultCode, it.decidedVia) shouldBe listOf("APPROVAL_EXPIRED", "DEADLINE_CONFIRM") }
            rows(ready, activation, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            clock.reset()
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = true)
        }
    }

    Given("B3-1-2: an evidence-backed site review approval and the 1-person rejection") {
        Then("approval: decision row seq 1 APPROVED with the approval evidence, reference site#1, EXECUTED evidence row, revision +1") {
            val (ready, _, _) = issued()
            val command = siteReviewApproval(ready.siteId)
            val revision = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", ready.merchantId.value)
            entitlement.approveSiteReviewWithEvidence(command) shouldBe AdminCommandResult.Succeeded("${ready.siteId.value}#1", replayed = false)
            text("SELECT CONCAT(decision, '/', reason, '/', evidence_ref, '/', decided_by) FROM merchant_site_review_decision WHERE merchant_site_id = ? AND seq = 1", ready.siteId.value) shouldBe
                "APPROVED/REVIEW_PASSED/${ApprovedCommandFixture.PG_REVIEW}/${ApprovalEvidenceFixtures.APPROVER.value}"
            count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", ready.merchantId.value) shouldBe revision + 1
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.operation, it.outcome, it.resultReference) shouldBe listOf("DecideSiteReview", "EXECUTED", "${ready.siteId.value}#1")
            }
        }

        Then("rejection (1 person): REJECTED under operation RejectSiteReview, no evidence row; a later approval is a stored SITE_REVIEW_TRANSITION_NOT_ALLOWED") {
            val (ready, _, _) = issued()
            val key = OnboardingFixture.key()
            entitlement.rejectSiteReview(RejectSiteReviewCommand(request(key), ready.siteId, DecisionReason("DOCUMENT_INSUFFICIENT"), null)) shouldBe
                AdminCommandResult.Succeeded("${ready.siteId.value}#1", replayed = false)
            text("SELECT operation FROM admin_request WHERE idempotency_key = ?", key.value) shouldBe "RejectSiteReview"
            text("SELECT decision FROM merchant_site_review_decision WHERE merchant_site_id = ? AND seq = 1", ready.siteId.value) shouldBe "REJECTED"
            text("SELECT change_type FROM master_change_history WHERE aggregate_id = ? ORDER BY id DESC LIMIT 1", ready.siteId.value) shouldBe "MerchantSiteReviewDecided"
            count("SELECT COUNT(*) FROM admin_approval_evidence e JOIN admin_request r ON r.request_record_id = e.request_record_id WHERE r.idempotency_key = ?", key.value) shouldBe 0
            val approval = siteReviewApproval(ready.siteId)
            entitlement.approveSiteReviewWithEvidence(approval) shouldBe AdminCommandResult.Rejected(MasterRejection.SITE_REVIEW_TRANSITION_NOT_ALLOWED, null, replayed = false)
            evidence(approval.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "SITE_REVIEW_TRANSITION_NOT_ALLOWED"
            count("SELECT COUNT(*) FROM merchant_site_review_decision WHERE merchant_site_id = ?", ready.siteId.value) shouldBe 1
        }
    }

    Given("B3-1-2 (review M-2): the evidence-backed site review approval has the same evidence matrix as the activation approval") {
        data class ReviewRows(
            val decisions: Long, val entitlement: Long, val snapshots: Long, val history: Long, val adminRequests: Long, val evidence: Long, val outbox: Long,
        )

        fun reviewRows(ready: OnboardingFixture.ReadyMerchant, approvalId: String, site: MerchantSiteId = ready.siteId) = ReviewRows(
            decisions = count("SELECT COUNT(*) FROM merchant_site_review_decision WHERE merchant_site_id = ?", site.value),
            entitlement = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", ready.merchantId.value),
            snapshots = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", ready.merchantId.value),
            history = count("SELECT COUNT(*) FROM master_change_history WHERE change_type = 'MerchantSiteReviewDecided' AND aggregate_id = ?", site.value),
            adminRequests = count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", "mop1.$approvalId"),
            evidence = count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", approvalId),
            // m-4(B3-1F): 이 Site의 통합 이벤트 + 그 가맹점 Snapshot Outbox.
            outbox = count(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? OR event_id IN (SELECT outbox_event_id FROM entitlement_snapshot_issue WHERE merchant_id = ?)",
                site.value, ready.merchantId.value,
            ),
        )

        Then("(a) a fault after the evidence row write, at the decision insert or at the result write rolls back everything; a retry then executes once") {
            listOf(MasterFaultPoint.APPROVAL_EVIDENCE_RECORD, MasterFaultPoint.REVIEW_APPEND, MasterFaultPoint.ADMIN_COMPLETE).forEach { point ->
                withClue(point) {
                    val (ready, _, _) = issued()
                    val command = siteReviewApproval(ready.siteId)
                    val before = reviewRows(ready, command.evidence.approvalId)
                    faults.after(point) { throw Injected() }
                    shouldThrow<Injected> { entitlement.approveSiteReviewWithEvidence(command) }
                    reviewRows(ready, command.evidence.approvalId) shouldBe before
                    faults.reset()
                    entitlement.approveSiteReviewWithEvidence(command) shouldBe AdminCommandResult.Succeeded("${ready.siteId.value}#1", replayed = false)
                    reviewRows(ready, command.evidence.approvalId) shouldBe before.copy(
                        decisions = 1, entitlement = before.entitlement + 1, snapshots = before.snapshots + 1, history = before.history + 1, adminRequests = 1, evidence = 1,
                        // m-4(B3-1F): Outbox = 심사 결정 1 + Snapshot 1.
                        outbox = before.outbox + 2,
                    )
                }
            }
        }

        Then("(b) the same actor is a stored APPROVAL_SAME_ACTOR with a REJECTED same-actor evidence row; no decision row") {
            val (ready, _, _) = issued()
            val command = siteReviewApproval(ready.siteId, ApprovalEvidenceFixtures.sameActor())
            val before = reviewRows(ready, command.evidence.approvalId)
            entitlement.approveSiteReviewWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_SAME_ACTOR, null, replayed = false)
            reviewRows(ready, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.outcome, it.resultCode, it.decidedVia) shouldBe listOf("REJECTED", "APPROVAL_SAME_ACTOR", "EXECUTE")
                it.requestedBy shouldBe it.approvedBy
            }
        }

        Then("(c) order (m-4): an expired evidence for an unknown site is SITE_NOT_FOUND (pre-lock lookup); for a known site it is APPROVAL_EXPIRED") {
            val missing = com.paycore.master.merchant.domain.MerchantSiteId("site_" + MasterFixture.ulid())
            val unknown = siteReviewApproval(missing, ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            entitlement.approveSiteReviewWithEvidence(unknown) shouldBe AdminCommandResult.Rejected(MasterRejection.SITE_NOT_FOUND, null, replayed = false)
            evidence(unknown.evidence.approvalId).shouldNotBeNull().resultCode shouldBe "SITE_NOT_FOUND"
            val (ready, _, _) = issued()
            val known = siteReviewApproval(ready.siteId, ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))
            entitlement.approveSiteReviewWithEvidence(known) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false)
            count("SELECT COUNT(*) FROM merchant_site_review_decision WHERE merchant_site_id = ?", ready.siteId.value) shouldBe 0
        }

        Then("D-MOPS-009 ③: the site creator's evidence approval is a stored APPROVER_IS_SITE_REGISTRANT rejection — no decision row, nothing else changes; the same bytes replay") {
            val (ready, _, _) = issued()
            val site = siteRegisteredByApprover(ready)
            val command = siteReviewApproval(site)
            val before = reviewRows(ready, command.evidence.approvalId, site)
            entitlement.approveSiteReviewWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVER_IS_SITE_REGISTRANT, null, replayed = false)
            reviewRows(ready, command.evidence.approvalId, site) shouldBe before.copy(adminRequests = 1, evidence = 1)
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.operation, it.outcome, it.resultCode, it.decidedVia) shouldBe listOf("DecideSiteReview", "REJECTED", "APPROVER_IS_SITE_REGISTRANT", "EXECUTE")
            }
            entitlement.approveSiteReviewWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVER_IS_SITE_REGISTRANT, null, replayed = true)
            reviewRows(ready, command.evidence.approvalId, site) shouldBe before.copy(adminRequests = 1, evidence = 1)
            // 대조군: 같은 Site를 다른 승인자가 승인하면 APPROVED seq 1(등록자만 막힌다).
            val other = siteReviewApproval(site, ApprovalEvidenceFixtures.distinctActors(approvedBy = ApprovalEvidenceFixtures.OTHER))
            entitlement.approveSiteReviewWithEvidence(other) shouldBe AdminCommandResult.Succeeded("${site.value}#1", replayed = false)
        }

        Then("D-MOPS-009 ③ order (m-4): expired evidence for the creator's site is APPROVAL_EXPIRED before the registrant check") {
            val (ready, _, _) = issued()
            val site = siteRegisteredByApprover(ready)
            entitlement.approveSiteReviewWithEvidence(siteReviewApproval(site, ApprovalEvidenceFixtures.expiredAt(OnboardingFixture.t0))) shouldBe
                AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false)
            count("SELECT COUNT(*) FROM merchant_site_review_decision WHERE merchant_site_id = ?", site.value) shouldBe 0
        }

        Then("(d) DecideSiteReview deadline confirmation: DEADLINE_CONFIRM, business state unchanged; the execute resend replays the expiry") {
            val (ready, _, _) = issued()
            val command = siteReviewApproval(ready.siteId)
            val before = reviewRows(ready, command.evidence.approvalId)
            clock.set(command.evidence.executeBefore)
            decisions.confirmDeadline(command) shouldBe
                DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false))
            evidence(command.evidence.approvalId).shouldNotBeNull().let {
                listOf(it.operation, it.outcome, it.resultCode, it.decidedVia) shouldBe listOf("DecideSiteReview", "REJECTED", "APPROVAL_EXPIRED", "DEADLINE_CONFIRM")
            }
            reviewRows(ready, command.evidence.approvalId) shouldBe before.copy(adminRequests = 1, evidence = 1)
            clock.reset()
            entitlement.approveSiteReviewWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = true)
            reviewRows(ready, command.evidence.approvalId).decisions shouldBe 0
        }
    }

    Given("B3-1-3 / C-40 (D-MOPS-008 ②): evidence-less 2-person entries run only on the Demo seed path") {
        Then("approvePaymentMethodActivation and decideSiteReview(APPROVED) refuse other actors and LIVE mode before any transaction; REJECTED/SUSPENDED are not gated") {
            val (ready, _, activation) = issued()
            listOf(ActorRef("system:other"), ApprovalEvidenceFixtures.APPROVER, OnboardingFixture.operator).forEach { actor ->
                withClue(actor.value) {
                    val key = OnboardingFixture.key()
                    shouldThrow<IllegalStateException> {
                        entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(request(key, actor), activation, EntitlementCommandFixture.evidence))
                    }
                    shouldThrow<IllegalStateException> {
                        entitlement.decideSiteReview(DecideSiteReviewCommand(request(key, actor), ready.siteId, SiteReviewStatus.APPROVED, EntitlementCommandFixture.reason, null))
                    }
                    count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", key.value) shouldBe 0
                }
            }
            mode.demo = false
            shouldThrow<IllegalStateException> {
                entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(seedRequest(), activation, EntitlementCommandFixture.evidence))
            }
            mode.reset()
            succeeded(entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(seedRequest(), activation, EntitlementCommandFixture.evidence)))
            succeeded(entitlement.decideSiteReview(DecideSiteReviewCommand(seedRequest(), ready.siteId, SiteReviewStatus.APPROVED, EntitlementCommandFixture.reason, null)))
            // 안전 방향(1인 — B5 범위)은 관문 없음: 운영자 행위자로 SUSPENDED.
            succeeded(entitlement.decideSiteReview(DecideSiteReviewCommand(request(), ready.siteId, SiteReviewStatus.SUSPENDED, EntitlementCommandFixture.reason, null)))
        }
    }

    Given("B3-1-2: the deadline confirmation for a B3 operation never runs the business body") {
        Then("after the deadline with no decision: stored APPROVAL_EXPIRED (decided_via DEADLINE_CONFIRM), activation unchanged; the execute resend replays that expiry") {
            val (ready, _, activation) = issued()
            val command = activationApproval(activation, ready.siteId)
            clock.set(command.evidence.executeBefore.minusMillis(1))
            decisions.confirmDeadline(command) shouldBe DeadlineConfirmationResult.NotYetDue
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value) shouldBe 0
            clock.set(command.evidence.executeBefore)
            decisions.confirmDeadline(command) shouldBe
                DeadlineConfirmationResult.Decided(AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = false))
            evidence(command.evidence.approvalId).shouldNotBeNull().decidedVia shouldBe "DEADLINE_CONFIRM"
            text("SELECT status FROM payment_method_activation WHERE activation_id = ?", activation.value) shouldBe "UNDER_REVIEW"
            clock.reset()
            entitlement.approvePaymentMethodActivationWithEvidence(command) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVAL_EXPIRED, null, replayed = true)
        }

        Then("invariant n-a over every approval operation including the B3-1 ones: each stored result has exactly one evidence row") {
            // B3-1F review NIT-4: 대조군 행을 이 시험 안에서 직접 만든다(앞 시험 실행 순서에 기대지 않음) — 등록자 거절 두 종류(개통·Site).
            val (actReady, _, actByApprover) = issued(activationRegistrant = ApprovalEvidenceFixtures.APPROVER)
            val activationRefused = activationApproval(actByApprover, actReady.siteId)
            entitlement.approvePaymentMethodActivationWithEvidence(activationRefused) shouldBe
                AdminCommandResult.Rejected(MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT, null, replayed = false)
            val (siteReady, _, _) = issued()
            val siteRefused = siteReviewApproval(siteRegisteredByApprover(siteReady))
            entitlement.approveSiteReviewWithEvidence(siteRefused) shouldBe AdminCommandResult.Rejected(MasterRejection.APPROVER_IS_SITE_REGISTRANT, null, replayed = false)
            runtime(container).use { ApprovedCommandFixture.invariantViolations(it) }.shouldBeEmpty()
            // 대조군: 불변식 조회가 B3-1 승인 행을 실제로 본다(방금 만든 두 행 — mop1 키).
            count(
                "SELECT COUNT(*) FROM admin_request WHERE operation IN ('ApprovePaymentMethodActivation', 'DecideSiteReview') AND idempotency_key IN (?, ?)",
                activationRefused.request.idempotencyKey.value, siteRefused.request.idempotencyKey.value,
            ) shouldBe 2
            // 대조군(B3-1F): 불변식 조회가 D-MOPS-009 ③ 등록자 거절 행(두 종류)도 실제로 본다 — 이 시험이 만든 두 승인 ID로 한정.
            count(
                "SELECT COUNT(DISTINCT result_code) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND result_code IN ('APPROVER_IS_ACTIVATION_REGISTRANT', 'APPROVER_IS_SITE_REGISTRANT')",
                activationRefused.evidence.approvalId, siteRefused.evidence.approvalId,
            ) shouldBe 2
        }
    }
})

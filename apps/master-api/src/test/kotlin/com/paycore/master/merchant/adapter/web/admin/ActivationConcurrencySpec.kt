package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.MasterFaultInjector
import com.paycore.master.merchant.application.MasterFaultPoint
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MERCHANT-OPS-002 §8.3 B3-1-4 Concurrency Control(실제 MySQL — 서명 채널 + 실제 Use Case, 요청마다 다른 DB 연결, latch로 시작을 맞춘다 — B2-2-7과 같은 방식). 3회씩:
 * ① 같은 Activation에 다른 approvalId 둘 → 1 SUCCEEDED·1 `ACTIVATION_TRANSITION_NOT_ALLOWED`, PRIMARY 확인 1행, 근거 EXECUTED 1·REJECTED 1.
 * ② 같은 가맹점의 개통 승인 + Site 심사 승인 → 둘 다 성공(merchant 잠금 직렬화), 권리 revision +2, Snapshot +2.
 * ③ 같은 MID 개통 등록 두 submissionId → 1 `act_…`, 1 `ACTIVATION_DUPLICATE`(거절 reference 키 없음 — C-12).
 * ④ minor-2: 같은 Site에 1인 거절과 2인 승인 → 결정 행 정확히 1(seq 1), 다른 하나 `SITE_REVIEW_TRANSITION_NOT_ALLOWED`, 승인이 졌으면 근거 REJECTED 행.
 * ⑤⑥ B3-1F-3(D-MOPS-009 ③): 등록자 근거(A)·다른 승인자 근거(B) 동시 → 항상 {A 등록자 거절, B SUCCEEDED}(등록자 판정이 전이 앞 — C-49), 진 쪽 무효과(m-4: 이력·revision·Snapshot·Outbox B분만).
 *   ⑤⑥은 순서를 강제하지 않는다(latch 동시 시작 — 어느 쪽이 먼저 잠그는지는 관측 결과).
 * ⑦⑧ B3-1F review MINOR-1: 순서 강제 — [MasterFaultInjector] `MERCHANT_LOCK` hook으로 B가 merchant 행 잠금을 쥔 채 A가 실제 DB 잠금 대기
 *   (performance_schema.data_lock_waits)에 들어갈 때까지 붙잡은 뒤 놓는다 → A는 항상 B 커밋 **뒤**(이미 ACTIVE/APPROVED)에 판정된다. 그래도 A = 등록자 거절
 *   (전이 거절이 아님)이어야 등록자 판정이 전이 앞이라는 C-49가 경합에서 증명된다(변이 b가 이 사례에서 ACTIVATION_TRANSITION_NOT_ALLOWED로 죽는다).
 */
@MasterDbTest
class ActivationConcurrencySpec(
    context: WebApplicationContext,
    private val container: MySQLContainer,
    private val onboarding: MerchantOnboardingUseCase,
    private val faults: MasterFaultInjector,
) : BehaviorSpec({

    afterEach { faults.reset() }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun post(path: String, body: String): Pair<Int, String> {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val r = mvc.perform(
            MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)),
        ).andReturn().response
        return r.status to r.getContentAsString(Charsets.UTF_8)
    }

    fun outcome(body: String): String = json.readTree(body).let { n -> n.get("rejection")?.asString() ?: n.get("outcome").asString() }
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun text(sql: String, vararg args: Any?): String = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString() }

    /** B3-1F m-4 진 쪽 무효과 집계(개통): 상태·전이 이력·권리 revision·Snapshot·Outbox(개통 + PRIMARY 확인 + 그 가맹점 Snapshot). */
    data class ActivationEffects(val status: String, val events: Long, val revision: Long, val snapshots: Long, val outbox: Long)

    /** B3-1F m-4 진 쪽 무효과 집계(Site 심사): 결정 행·최신 결정·권리 revision·Snapshot·Outbox(Site 통합 이벤트 + 그 가맹점 Snapshot). */
    data class SiteEffects(val decisions: Long, val latest: String, val revision: Long, val snapshots: Long, val outbox: Long)

    fun snapshotOutboxClause() = "event_id IN (SELECT outbox_event_id FROM entitlement_snapshot_issue WHERE merchant_id = ?)"

    fun activationEffects(act: String, merchantId: String) = ActivationEffects(
        status = text("SELECT status FROM payment_method_activation WHERE activation_id = ?", act),
        events = count("SELECT COUNT(*) FROM payment_method_activation_event WHERE activation_id = ?", act),
        revision = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", merchantId),
        snapshots = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", merchantId),
        outbox = count(
            "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? OR aggregate_id IN (SELECT confirmation_id FROM site_activation_confirmation WHERE activation_id = ?) OR " +
                snapshotOutboxClause(),
            act, act, merchantId,
        ),
    )

    fun siteEffects(site: String, merchantId: String) = SiteEffects(
        decisions = count("SELECT COUNT(*) FROM merchant_site_review_decision WHERE merchant_site_id = ?", site),
        latest = text("SELECT COALESCE((SELECT decision FROM merchant_site_review_decision WHERE merchant_site_id = ? ORDER BY seq DESC LIMIT 1), 'PENDING')", site),
        revision = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", merchantId),
        snapshots = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", merchantId),
        outbox = count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? OR " + snapshotOutboxClause(), site, merchantId),
    )

    fun evidenceOf(approvalId: String) = text(
        "SELECT CONCAT(outcome, '/', COALESCE(result_code, '-'), '/', decided_via) FROM admin_approval_evidence WHERE approval_id = ?", approvalId,
    )

    /** 실제 DB 잠금 대기 TX 수(FirstDefaultIssuanceAtomicitySpec과 같은 관측 — root 계정, performance_schema). */
    fun lockWaiters(): Int = MasterDbAccounts.root(container).use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits").use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    fun awaitLockWaiters(expected: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (lockWaiters() >= expected) return
            Thread.sleep(10)
        }
        error("expected $expected lock-waiting transactions, observed ${lockWaiters()}")
    }

    /**
     * MINOR-1 결정적 순서: [first]가 merchant 행 잠금(`MERCHANT_LOCK` 직후 — 같은 TX·commit 전)을 쥔 것을 확인한 뒤 [second]를 시작하고, [second]가 실제 잠금 대기에 들어간 것을
     * 관측한 다음에야 [first]를 놓는다. 반환: (first 결과, second 결과, 대기 관측 여부).
     */
    fun firstHoldsThenSecond(first: () -> Pair<Int, String>, second: () -> Pair<Int, String>): Triple<Pair<Int, String>, Pair<Int, String>, Boolean> {
        val holding = CountDownLatch(1)
        val held = AtomicBoolean(false)
        val waiterObserved = AtomicBoolean(false)
        faults.after(MasterFaultPoint.MERCHANT_LOCK) {
            if (held.compareAndSet(false, true)) {
                holding.countDown()
                awaitLockWaiters(1)
                waiterObserved.set(true)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstResult = pool.submit<Pair<Int, String>> { first() }
            holding.await(10, TimeUnit.SECONDS) shouldBe true
            val secondResult = pool.submit<Pair<Int, String>> { second() }
            return Triple(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS), waiterObserved.get())
        } finally {
            faults.reset()
            pool.shutdownNow()
        }
    }

    fun requestResultOf(key: String) = text("SELECT CONCAT(result_kind, '/', COALESCE(result_code, '-')) FROM admin_request WHERE idempotency_key = ?", key)

    Given("B3-1 races on real MySQL connections (3 rounds each)") {
        Then("① two approvals of the same activation: one SUCCEEDED, one ACTIVATION_TRANSITION_NOT_ALLOWED; one PRIMARY confirmation; evidence EXECUTED 1 / REJECTED 1") {
            repeat(3) { round ->
                val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding)
                val act = ids.second.value
                val approvals = listOf(Approval(), Approval())
                val results = OnboardingFixture.concurrently(2) { i ->
                    post(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, ActivationBodies.activationApproval(act, ready.siteId.value, approvals[i]))
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { it.first } shouldBe listOf(200, 200)
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "ACTIVATION_TRANSITION_NOT_ALLOWED")
                    count("SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?", act) shouldBe 1L
                    val a = approvals.map { it.approvalId }
                    count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'EXECUTED'", a[0], a[1]) shouldBe 1L
                    count(
                        "SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'REJECTED' AND result_code = 'ACTIVATION_TRANSITION_NOT_ALLOWED'", a[0], a[1],
                    ) shouldBe 1L
                }
            }
        }

        Then("② activation approval and site review approval of the same merchant at once: both succeed, revision +2, two Snapshots") {
            repeat(3) { round ->
                val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding)
                val revision = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", ready.merchantId.value)
                val snapshots = count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", ready.merchantId.value)
                val results = OnboardingFixture.concurrently(2) { i ->
                    if (i == 0) {
                        post(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, ActivationBodies.activationApproval(ids.second.value, ready.siteId.value))
                    } else {
                        post(ActivationBodies.SITE_REVIEW_APPROVALS, ActivationBodies.siteReviewApproval(ready.siteId.value))
                    }
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { outcome(it.second) } shouldBe listOf("SUCCEEDED", "SUCCEEDED")
                    count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?", ready.merchantId.value) shouldBe revision + 2
                    count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ?", ready.merchantId.value) shouldBe snapshots + 2
                }
            }
        }

        Then("③ two registrations of the same MID's CARD activation: one act_, one ACTIVATION_DUPLICATE without a reference key") {
            repeat(3) { round ->
                val ready = OnboardingFixture.readyMerchant(onboarding)
                val mid = OnboardingFixture.succeeded(onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand()))
                val results = OnboardingFixture.concurrently(2) {
                    post(ActivationBodies.PAYMENT_METHOD_ACTIVATIONS, ActivationBodies.registerActivation(mid, Submission()))
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "ACTIVATION_DUPLICATE")
                    results.single { outcome(it.second) == "ACTIVATION_DUPLICATE" }.second.contains("reference") shouldBe false
                    count("SELECT COUNT(*) FROM payment_method_activation WHERE merchant_account_id = ?", mid) shouldBe 1L
                }
            }
        }

        Then("④ minor-2: a 1-person rejection and a 2-person approval of the same site at once: exactly one decision row; the other is SITE_REVIEW_TRANSITION_NOT_ALLOWED") {
            repeat(3) { round ->
                val ready = OnboardingFixture.readyMerchant(onboarding)
                val site = ready.siteId.value
                val approval = Approval()
                val results = OnboardingFixture.concurrently(2) { i ->
                    if (i == 0) {
                        post(ActivationBodies.SITE_REVIEW_REJECTIONS, ActivationBodies.rejectSiteReview(site))
                    } else {
                        post(ActivationBodies.SITE_REVIEW_APPROVALS, ActivationBodies.siteReviewApproval(site, approval))
                    }
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "SITE_REVIEW_TRANSITION_NOT_ALLOWED")
                    count("SELECT COUNT(*) FROM merchant_site_review_decision WHERE merchant_site_id = ?", site) shouldBe 1L
                    val approvalLost = outcome(results[1].second) == "SITE_REVIEW_TRANSITION_NOT_ALLOWED"
                    val expected = if (approvalLost) "REJECTED" else "EXECUTED"
                    count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ? AND outcome = ?", approval.approvalId, expected) shouldBe 1L
                }
            }
        }

        Then("⑤ D-MOPS-009 ③: the registrant's evidence (A) and another approver's (B) on the same activation at once → always {A APPROVER_IS_ACTIVATION_REGISTRANT, B SUCCEEDED}; A has no effect (m-4)") {
            repeat(3) { round ->
                val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding, activationRegistrant = ApprovalEvidenceFixtures.APPROVER)
                val act = ids.second.value
                val m = ready.merchantId.value
                val a = Approval() // 승인자 = APPROVER = 개통 등록자
                val b = Approval(approvedBy = ApprovalEvidenceFixtures.OTHER.value)
                val before = activationEffects(act, m)
                val results = OnboardingFixture.concurrently(2) { i ->
                    post(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, ActivationBodies.activationApproval(act, ready.siteId.value, if (i == 0) a else b))
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { it.first } shouldBe listOf(200, 200)
                    // 등록자 판정이 전이 판정 앞(C-49)이라 잠금 순서와 무관하게 A는 등록자 거절(뒤여도 ACTIVATION_TRANSITION_NOT_ALLOWED가 아님).
                    results.map { outcome(it.second) } shouldBe listOf("APPROVER_IS_ACTIVATION_REGISTRANT", "SUCCEEDED")
                    count("SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?", act) shouldBe 1L
                    evidenceOf(a.approvalId) shouldBe "REJECTED/APPROVER_IS_ACTIVATION_REGISTRANT/EXECUTE"
                    evidenceOf(b.approvalId) shouldBe "EXECUTED/-/EXECUTE"
                    requestResultOf(a.idempotencyKey) shouldBe "REJECTED/APPROVER_IS_ACTIVATION_REGISTRANT"
                    // 진 쪽(A) 무효과: 경합 전 대비 B 한 건분만(전이 1·revision 1·Snapshot 1·Outbox = 전이 + PRIMARY 확인 + Snapshot).
                    activationEffects(act, m) shouldBe before.copy(
                        status = "ACTIVE", events = before.events + 1, revision = before.revision + 1, snapshots = before.snapshots + 1, outbox = before.outbox + 3,
                    )
                }
            }
        }

        Then("⑥ D-MOPS-009 ③: the site creator's evidence (A) and another approver's (B) on the same site at once → always {A APPROVER_IS_SITE_REGISTRANT, B SUCCEEDED}; A has no effect (m-4)") {
            repeat(3) { round ->
                val (ready, _) = EntitlementCommandFixture.issuedWithActivation(onboarding)
                val m = ready.merchantId.value
                val site = OnboardingFixture.succeeded(
                    onboarding.registerMerchantSite(
                        RegisterMerchantSiteCommand(OnboardingFixture.request(actor = ApprovalEvidenceFixtures.APPROVER), ready.merchantId, ChannelType.WEB, OnboardingFixture.host()),
                    ),
                )
                val a = Approval() // 승인자 = APPROVER = Site 등록자
                val b = Approval(approvedBy = ApprovalEvidenceFixtures.OTHER.value)
                val before = siteEffects(site, m)
                val results = OnboardingFixture.concurrently(2) { i ->
                    post(ActivationBodies.SITE_REVIEW_APPROVALS, ActivationBodies.siteReviewApproval(site, if (i == 0) a else b))
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { outcome(it.second) } shouldBe listOf("APPROVER_IS_SITE_REGISTRANT", "SUCCEEDED")
                    evidenceOf(a.approvalId) shouldBe "REJECTED/APPROVER_IS_SITE_REGISTRANT/EXECUTE"
                    evidenceOf(b.approvalId) shouldBe "EXECUTED/-/EXECUTE"
                    requestResultOf(a.idempotencyKey) shouldBe "REJECTED/APPROVER_IS_SITE_REGISTRANT"
                    siteEffects(site, m) shouldBe before.copy(
                        decisions = before.decisions + 1, latest = "APPROVED", revision = before.revision + 1, snapshots = before.snapshots + 1, outbox = before.outbox + 2,
                    )
                }
            }
        }

        Then("⑦ MINOR-1 forced order: B (another approver) holds the merchant lock, A (the activation registrant) waits on it and is decided after B commits → A is still APPROVER_IS_ACTIVATION_REGISTRANT (not a transition rejection)") {
            val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding, activationRegistrant = ApprovalEvidenceFixtures.APPROVER)
            val act = ids.second.value
            val m = ready.merchantId.value
            val a = Approval() // 승인자 = APPROVER = 개통 등록자
            val b = Approval(approvedBy = ApprovalEvidenceFixtures.OTHER.value)
            val before = activationEffects(act, m)
            val (bResult, aResult, waited) = firstHoldsThenSecond(
                { post(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, ActivationBodies.activationApproval(act, ready.siteId.value, b)) },
                { post(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, ActivationBodies.activationApproval(act, ready.siteId.value, a)) },
            )
            // 순서 증거: A가 B의 잠금을 실제로 기다렸다(관측) → A의 판정은 B 커밋 뒤(그 시점 개통은 이미 ACTIVE).
            waited shouldBe true
            listOf(bResult.first, aResult.first) shouldBe listOf(200, 200)
            listOf(outcome(bResult.second), outcome(aResult.second)) shouldBe listOf("SUCCEEDED", "APPROVER_IS_ACTIVATION_REGISTRANT")
            evidenceOf(a.approvalId) shouldBe "REJECTED/APPROVER_IS_ACTIVATION_REGISTRANT/EXECUTE"
            evidenceOf(b.approvalId) shouldBe "EXECUTED/-/EXECUTE"
            requestResultOf(a.idempotencyKey) shouldBe "REJECTED/APPROVER_IS_ACTIVATION_REGISTRANT"
            count("SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?", act) shouldBe 1L
            activationEffects(act, m) shouldBe before.copy(
                status = "ACTIVE", events = before.events + 1, revision = before.revision + 1, snapshots = before.snapshots + 1, outbox = before.outbox + 3,
            )
        }

        Then("⑧ MINOR-1 forced order: B holds the merchant lock, A (the site creator) waits and is decided after B commits → A is still APPROVER_IS_SITE_REGISTRANT (not SITE_REVIEW_TRANSITION_NOT_ALLOWED)") {
            val (ready, _) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val m = ready.merchantId.value
            val site = OnboardingFixture.succeeded(
                onboarding.registerMerchantSite(
                    RegisterMerchantSiteCommand(OnboardingFixture.request(actor = ApprovalEvidenceFixtures.APPROVER), ready.merchantId, ChannelType.WEB, OnboardingFixture.host()),
                ),
            )
            val a = Approval() // 승인자 = APPROVER = Site 등록자
            val b = Approval(approvedBy = ApprovalEvidenceFixtures.OTHER.value)
            val before = siteEffects(site, m)
            val (bResult, aResult, waited) = firstHoldsThenSecond(
                { post(ActivationBodies.SITE_REVIEW_APPROVALS, ActivationBodies.siteReviewApproval(site, b)) },
                { post(ActivationBodies.SITE_REVIEW_APPROVALS, ActivationBodies.siteReviewApproval(site, a)) },
            )
            waited shouldBe true
            listOf(outcome(bResult.second), outcome(aResult.second)) shouldBe listOf("SUCCEEDED", "APPROVER_IS_SITE_REGISTRANT")
            evidenceOf(a.approvalId) shouldBe "REJECTED/APPROVER_IS_SITE_REGISTRANT/EXECUTE"
            evidenceOf(b.approvalId) shouldBe "EXECUTED/-/EXECUTE"
            requestResultOf(a.idempotencyKey) shouldBe "REJECTED/APPROVER_IS_SITE_REGISTRANT"
            siteEffects(site, m) shouldBe before.copy(
                decisions = before.decisions + 1, latest = "APPROVED", revision = before.revision + 1, snapshots = before.snapshots + 1, outbox = before.outbox + 2,
            )
        }
    }
})

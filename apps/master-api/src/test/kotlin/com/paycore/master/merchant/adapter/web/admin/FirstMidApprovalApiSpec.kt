package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.application.ApprovedCommandFixture
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * MERCHANT-OPS-002 §8.2 B2-3(실제 MySQL — 서명 채널 + 실제 Use Case): `first-default-merchant-accounts`가 B2-2와 같은 근거 행렬을 쓴다.
 * - 성공 `mid_…` + 근거 EXECUTED 행(같은 TX — 불변식 n-a), 결정 조회 operation = `IssueFirstDefaultMerchantAccount`, 기한 확인은 같은 바이트로 재생.
 * - Revision 등록자 규칙은 계약 승인에만 — **등록자 = 승인자여도 MID 발급은 통과**(시험으로 고정). 같은 행위자는 저장된 거절 `APPROVAL_SAME_ACTOR`.
 * - 경합(B2-3-3): 같은 가맹점에 다른 approvalId 둘 동시 → 1 `mid_…`, 1 `DEFAULT_ALREADY_ISSUED`(merchant 잠금 직렬화), MID 1개.
 * 명시 Fixture: [OnboardingFixture.readyMerchant](계약 Revision 1 승인됨·CSA·ACTIVE Site), 근거는 [Approval] 기본값(시험 clock t0 기준 승인 1분 전·기한 +1시간).
 */
@MasterDbTest
class FirstMidApprovalApiSpec(
    context: WebApplicationContext,
    private val container: MySQLContainer,
    private val onboarding: MerchantOnboardingUseCase,
    private val registry: io.micrometer.core.instrument.MeterRegistry,
    private val permits: AdminChannelPermits,
    private val clock: com.paycore.master.merchant.application.MutableMasterClock,
    private val faults: com.paycore.master.merchant.application.MasterFaultInjector,
) : BehaviorSpec({

    beforeEach { clock.reset() }
    afterEach {
        clock.reset()
        faults.reset()
    }

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
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun body(r: OnboardingFixture.ReadyMerchant, a: Approval = Approval()) =
        MerchantApprovalBodies.firstDefaultIssuance(r.merchantId.value, r.contractId.value, r.agreementId.value, r.siteId.value, a = a)
    fun outcome(text: String): String = json.readTree(text).let { n -> n.get("rejection")?.asString() ?: n.get("outcome").asString() }

    Given("B2-3-1 the approved first Default MID issuance") {
        Then("200 SUCCEEDED mid_…; MID 1·Default; evidence EXECUTED row (operation, reference) in the same TX; decision lookup and deadline confirmation agree") {
            val r = OnboardingFixture.readyMerchant(onboarding)
            val a = Approval()
            val b = body(r, a)
            val (status, text) = post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, b)
            status shouldBe 200
            val node = json.readTree(text)
            node.propertyNames().toList() shouldBe listOf("outcome", "reference", "replayed")
            val mid = node.get("reference").asString()
            mid shouldMatch Regex("^mid_[0-9A-HJKMNP-TV-Z]{26}$")
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", r.merchantId.value) shouldBe 1L
            OnboardingFixture.runtime(container).use { c ->
                val row = ApprovedCommandFixture.evidenceRow(c, a.approvalId)!!
                listOf(row.operation, row.outcome, row.resultReference, row.decidedVia, row.approvedBy) shouldBe
                    listOf("IssueFirstDefaultMerchantAccount", "EXECUTED", mid, "EXECUTE", a.approvedBy)
                ApprovedCommandFixture.invariantViolations(c) shouldBe emptyList()
            }
            val decision = json.readTree(post(MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.decision(a.approvalId)).second)
            listOf(decision.get("decision").asString(), decision.get("operation").asString(), decision.get("reference").asString()) shouldBe
                listOf("DECIDED", "IssueFirstDefaultMerchantAccount", mid)
            val replay = json.readTree(post(MerchantApprovalBodies.DEADLINE_CONFIRMATIONS, b).second)
            listOf(replay.get("outcome").asString(), replay.get("reference").asString(), replay.get("replayed").asBoolean()) shouldBe listOf("SUCCEEDED", mid, true)
        }

        Then("the revision-registrant rule is for the contract approval only: registrant = approver still issues the MID; same actor is a stored APPROVAL_SAME_ACTOR") {
            val registrantIsApprover = OnboardingFixture.readyMerchant(onboarding, contractActor = ActorRef(ApprovalEvidenceFixtures.APPROVER.value))
            outcome(post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(registrantIsApprover)).second) shouldBe "SUCCEEDED"
            val same = OnboardingFixture.readyMerchant(onboarding)
            val a = Approval(requestedBy = ApprovalEvidenceFixtures.APPROVER.value)
            outcome(post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(same, a)).second) shouldBe "APPROVAL_SAME_ACTOR"
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", same.merchantId.value) shouldBe 0L
            OnboardingFixture.runtime(container).use { ApprovedCommandFixture.evidenceRow(it, a.approvalId)!!.outcome } shouldBe "REJECTED"
        }

        Then("business rules stay MASTER's: an unapproved revision → REVISION_NOT_APPROVED (stored, no MID)") {
            val r = OnboardingFixture.readyMerchant(onboarding, approved = false)
            outcome(post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(r)).second) shouldBe "REVISION_NOT_APPROVED"
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", r.merchantId.value) shouldBe 0L
        }
    }

    Given("B2-3-1 review m-2: the B2-2 1~7 matrix rows for the MID operation") {
        fun executed() = registry.find(MerchantApprovalController.APPROVAL_EXECUTED_METRIC).tags("operation", "IssueFirstDefaultMerchantAccount").counter()?.count() ?: 0.0

        Then("the same key with other evidence is 409 IDEMPOTENCY_KEY_REUSED (one request row, one evidence row); approval_executed{operation=IssueFirstDefaultMerchantAccount} counts the first execution only") {
            val r = OnboardingFixture.readyMerchant(onboarding)
            val a = Approval()
            val before = executed()
            outcome(post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(r, a)).second) shouldBe "SUCCEEDED"
            json.readTree(post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(r, a)).second).get("replayed").asBoolean() shouldBe true
            executed() shouldBe before + 1.0
            post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(r, a.copy(evidenceRef = "OTHER-DOC-9"))) shouldBe (409 to """{"error":"IDEMPOTENCY_KEY_REUSED"}""")
            listOf(
                count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey),
                count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId),
            ) shouldBe listOf(1L, 1L)
        }

        Then("C-1: with every write permit held the MID execution is 503 ADMIN_WRITE_BUSY and nothing is stored") {
            val writes = MasterAdminChannelFixture.springSettings().writePermits
            val held = java.util.concurrent.CountDownLatch(writes)
            val release = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(writes)
            val r = OnboardingFixture.readyMerchant(onboarding)
            val a = Approval()
            try {
                repeat(writes) { pool.submit { permits.write("hold", { error("permit expected") }) { held.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) } } }
                held.await(5, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
                post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(r, a)) shouldBe (503 to """{"error":"ADMIN_WRITE_BUSY"}""")
            } finally {
                release.countDown()
                pool.shutdown()
                pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 0L
        }

        Then("past the deadline without an execution: the deadline confirmation stores APPROVAL_EXPIRED (DEADLINE_CONFIRM), no MID; a later execution resend replays the expiry") {
            val r = OnboardingFixture.readyMerchant(onboarding)
            val a = Approval()
            val b = body(r, a)
            clock.set(java.time.Instant.parse(a.executeBefore))
            post(MerchantApprovalBodies.DEADLINE_CONFIRMATIONS, b) shouldBe (200 to """{"outcome":"REJECTED","rejection":"APPROVAL_EXPIRED","replayed":false}""")
            clock.reset()
            post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, b) shouldBe (200 to """{"outcome":"REJECTED","rejection":"APPROVAL_EXPIRED","replayed":true}""")
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", r.merchantId.value) shouldBe 0L
            OnboardingFixture.runtime(container).use { ApprovedCommandFixture.evidenceRow(it, a.approvalId)!!.let { e -> e.resultCode to e.decidedVia } } shouldBe
                ("APPROVAL_EXPIRED" to "DEADLINE_CONFIRM")
        }
    }

    Given("B2-3-3 Concurrency Control (real MySQL — requests on separate connections, latch start)") {
        Then("review m-2: the loser waits on the merchant lock (observed in data_lock_waits), then stores DEFAULT_ALREADY_ISSUED — evidence rows EXECUTED 1 and REJECTED 1") {
            val r = OnboardingFixture.readyMerchant(onboarding)
            val approvals = listOf(Approval(), Approval())
            val held = java.util.concurrent.atomic.AtomicBoolean(false)
            val waited = java.util.concurrent.atomic.AtomicInteger(0)
            faults.after(com.paycore.master.merchant.application.MasterFaultPoint.FIRST_DEFAULT_INSERT) {
                if (held.compareAndSet(false, true)) {
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (System.nanoTime() < deadline) {
                        val waiters = com.paycore.master.MasterDbAccounts.root(container).use { c ->
                            c.createStatement().use { st -> st.executeQuery("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits").use { rs -> rs.next(); rs.getInt(1) } }
                        }
                        if (waiters >= 1) { waited.set(waiters); break }
                        Thread.sleep(10)
                    }
                }
            }
            val results = OnboardingFixture.concurrently(2) { i -> post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(r, approvals[i])) }.map { it.getOrThrow() }
            waited.get() shouldBe 1
            results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "DEFAULT_ALREADY_ISSUED")
            val ids = approvals.map { it.approvalId }
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'EXECUTED'", ids[0], ids[1]) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'REJECTED' AND result_code = 'DEFAULT_ALREADY_ISSUED'", ids[0], ids[1]) shouldBe 1L
            count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", r.merchantId.value) shouldBe 1L
        }
        Then("two different approvalIds for the same merchant at once (3 rounds): one mid_…, one DEFAULT_ALREADY_ISSUED; exactly one MID") {
            repeat(3) { round ->
                val r = OnboardingFixture.readyMerchant(onboarding)
                val approvals = listOf(Approval(), Approval())
                val results = OnboardingFixture.concurrently(2) { i -> post(MerchantApprovalBodies.FIRST_DEFAULT_MERCHANT_ACCOUNTS, body(r, approvals[i])) }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { it.first } shouldBe listOf(200, 200)
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "DEFAULT_ALREADY_ISSUED")
                    count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?", r.merchantId.value) shouldBe 1L
                }
            }
        }
    }
})

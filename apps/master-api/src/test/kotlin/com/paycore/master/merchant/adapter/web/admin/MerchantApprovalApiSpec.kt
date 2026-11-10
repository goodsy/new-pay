package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * MERCHANT-OPS-002 §8.2 B2-2-5 / §9.4(실제 보안 필터 체인 + MVC + MySQL): 승인 3 endpoint의 서명 강제(무서명 401), 응답 모양(§3.3 실행·기한 확인, §9.4 결정 조회 — 거절에 reference 없음 C-12),
 * 같은 키 재생·다른 근거 409, 엄격 본문 400(저장 없음), 쓰기 permit 공유 503(결정 조회는 조회 permit), 지표 `approval_rejected{operation,rejection}`(근거 거절 5종만)·`approval_executed{operation}`,
 * 로그에 서명 키·서명·멱등 키 원문·근거 참조 값 없음(correlationId `mop.<approvalId>`는 있음).
 */
@MasterDbTest
class MerchantApprovalApiSpec(
    context: WebApplicationContext,
    private val registry: MeterRegistry,
    private val container: MySQLContainer,
    private val permits: AdminChannelPermits,
    private val clock: MutableMasterClock,
    private val onboarding: MerchantOnboardingUseCase,
) : BehaviorSpec({

    beforeEach { clock.reset() }
    afterEach { clock.reset() }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun post(path: String, body: String, header: String? = MasterAdminChannelFixture.springHeader("POST", path, body.toByteArray(Charsets.UTF_8))): MvcResult {
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body.toByteArray(Charsets.UTF_8))
        return mvc.perform(if (header == null) b else b.header(AdminCallSignature.HEADER, header)).andReturn()
    }

    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }
    fun rejectedMetric(rejection: String) =
        registry.find(MerchantApprovalController.APPROVAL_REJECTED_METRIC).tags("operation", "ApproveContractRevision", "rejection", rejection).counter()?.count() ?: 0.0
    fun executedMetric() = registry.find(MerchantApprovalController.APPROVAL_EXECUTED_METRIC).tags("operation", "ApproveContractRevision").counter()?.count() ?: 0.0

    /** 승인 전 Revision 1이 있는 계약(등록자 operator-1). */
    fun contract(): String = OnboardingFixture.readyMerchant(onboarding, approved = false).contractId.value

    Given("the three approval handlers behind the signed admin channel") {
        val paths = listOf(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.DEADLINE_CONFIRMATIONS)

        Then("unsigned, foreign-key and stale calls are 401 UNAUTHENTICATED without reaching a use case (no admin_request row)") {
            val before = count("SELECT COUNT(*) FROM admin_request")
            val body = MerchantApprovalBodies.contractRevisionApproval("ctr_01JC0000000000000000000CT1")
            val bytes = body.toByteArray(Charsets.UTF_8)
            paths.forEach { path ->
                listOf(
                    null,
                    MasterAdminChannelFixture.header("POST", path, bytes),
                    MasterAdminChannelFixture.springHeader("POST", path, bytes, t = Instant.now().epochSecond - 400),
                ).forEach { header ->
                    withClue("$path $header") {
                        val r = post(path, body, header)
                        r.response.status shouldBe 401
                        r.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
                    }
                }
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }

        Then("malformed bodies (actor field, fingerprint mismatch, key mismatch, decision with extra key) are 400 REQUEST_MALFORMED and nothing is stored") {
            val contractId = "ctr_01JC0000000000000000000CT1"
            val a = Approval()
            val fields = MerchantApprovalBodies.contractRevisionApprovalFields(contractId, a = a)
            val before = count("SELECT COUNT(*) FROM admin_request")
            listOf(
                MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS to MerchantApprovalBodies.with(fields, "actor", MerchantOperationsBodies.str(a.approvedBy)),
                MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS to MerchantApprovalBodies.with(fields, "approvalFingerprint", MerchantOperationsBodies.str("mop1:" + "0".repeat(64))),
                MerchantApprovalBodies.DEADLINE_CONFIRMATIONS to MerchantApprovalBodies.with(fields, "idempotencyKey", MerchantOperationsBodies.str("mo1.x")),
                MerchantApprovalBodies.APPROVAL_DECISIONS to MerchantApprovalBodies.decision(a.approvalId).dropLast(1) + ""","evidenceRef":"x"}""",
            ).forEach { (path, body) ->
                withClue(path) {
                    val r = post(path, body)
                    r.response.status shouldBe 400
                    r.body() shouldBe """{"error":"REQUEST_MALFORMED"}"""
                }
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }
    }

    Given("contract-revision-approvals (execute)") {
        Then("success is 200 {outcome, reference ctr_…#1, replayed}; resend replays; the same key with other evidence is 409; approval_executed counts the first execution only") {
            val contractId = contract()
            val a = Approval()
            val body = MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)
            val executedBefore = executedMetric()
            val first = post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, body)
            first.response.status shouldBe 200
            first.response.getHeader("Cache-Control")!! shouldContain "no-store"
            first.body() shouldBe """{"outcome":"SUCCEEDED","reference":"$contractId#1","replayed":false}"""
            post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, body).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$contractId#1","replayed":true}"""
            executedMetric() shouldBe executedBefore + 1.0

            val other = post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.contractRevisionApproval(contractId, a = a.copy(evidenceRef = "OTHER-DOC-9")))
            other.response.status shouldBe 409
            other.body() shouldBe """{"error":"IDEMPOTENCY_KEY_REUSED"}"""
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId) shouldBe 1L
            OnboardingFixture.runtime(container).use {
                it.scalar("SELECT actor_ref FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey)
            } shouldBe a.approvedBy
        }

        Then("C-12: a stored rejection is 200 {outcome REJECTED, rejection, replayed} without a reference key; approval_rejected counts only the five evidence codes") {
            val contractId = contract()
            val same = Approval(approvedBy = ApprovalEvidenceFixtures.REQUESTER.value)
            val sameBefore = rejectedMetric("APPROVAL_SAME_ACTOR")
            val rejected = post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.contractRevisionApproval(contractId, a = same))
            rejected.response.status shouldBe 200
            rejected.body() shouldBe """{"outcome":"REJECTED","rejection":"APPROVAL_SAME_ACTOR","replayed":false}"""
            json.readTree(rejected.body()).has("reference") shouldBe false
            rejectedMetric("APPROVAL_SAME_ACTOR") shouldBe sameBefore + 1.0

            succeeded(post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.contractRevisionApproval(contractId)))
            val again = post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.contractRevisionApproval(contractId))
            again.body() shouldBe """{"outcome":"REJECTED","rejection":"REVISION_ALREADY_APPROVED","replayed":false}"""
            rejectedMetric("REVISION_ALREADY_APPROVED") shouldBe 0.0
        }
    }

    Given("approval-decisions and approval-deadline-confirmations") {
        Then("decisions: NONE, then DECIDED with the documented fields in order (reference only on success, decidedVia); no reference on a rejection") {
            val unknown = ApprovalEvidenceFixtures.approvalId()
            post(MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.decision(unknown)).let {
                it.response.status shouldBe 200
                it.body() shouldBe """{"decision":"NONE"}"""
            }
            val contractId = contract()
            val a = Approval()
            succeeded(post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)))
            val fp = a.fingerprint(com.paycore.master.merchant.application.provided.AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId, "1"))
            post(MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.decision(a.approvalId)).body() shouldBe
                """{"decision":"DECIDED","operation":"ApproveContractRevision","outcome":"SUCCEEDED","reference":"$contractId#1","approvalFingerprint":"$fp","decidedVia":"EXECUTE"}"""

            val same = Approval(approvedBy = ApprovalEvidenceFixtures.REQUESTER.value)
            post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.contractRevisionApproval(contract(), a = same))
            val decided = post(MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.decision(same.approvalId)).body()
            json.readTree(decided).propertyNames().toList() shouldBe listOf("decision", "operation", "outcome", "rejection", "approvalFingerprint", "decidedVia")
            json.readTree(decided).get("rejection").asString() shouldBe "APPROVAL_SAME_ACTOR"
        }

        Then("deadline: before it NOT_YET_DUE (no row); at it the stored APPROVAL_EXPIRED in the §3.3 shape; the execution resend replays it") {
            val contractId = contract()
            val a = Approval()
            val body = MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)
            val expiredBefore = rejectedMetric("APPROVAL_EXPIRED")
            post(MerchantApprovalBodies.DEADLINE_CONFIRMATIONS, body).let {
                it.response.status shouldBe 200
                it.body() shouldBe """{"decision":"NOT_YET_DUE"}"""
            }
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 0L
            clock.set(Instant.parse(a.executeBefore))
            post(MerchantApprovalBodies.DEADLINE_CONFIRMATIONS, body).body() shouldBe """{"outcome":"REJECTED","rejection":"APPROVAL_EXPIRED","replayed":false}"""
            rejectedMetric("APPROVAL_EXPIRED") shouldBe expiredBefore + 1.0
            clock.reset()
            post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, body).body() shouldBe """{"outcome":"REJECTED","rejection":"APPROVAL_EXPIRED","replayed":true}"""
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", contractId) shouldBe 0L
        }

        Then("C-1: with every write permit held, execute and deadline confirmation are 503 ADMIN_WRITE_BUSY (no row) while the decision query still answers (read permit)") {
            val writes = MasterAdminChannelFixture.springSettings().writePermits
            val held = CountDownLatch(writes)
            val release = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writes)
            val a = Approval()
            val body = MerchantApprovalBodies.contractRevisionApproval("ctr_01JC0000000000000000000CT1", a = a)
            val before = count("SELECT COUNT(*) FROM admin_request")
            try {
                repeat(writes) { pool.submit { permits.write("hold", { error("permit expected") }) { held.countDown(); release.await(10, TimeUnit.SECONDS) } } }
                held.await(5, TimeUnit.SECONDS) shouldBe true
                listOf(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.DEADLINE_CONFIRMATIONS).forEach { path ->
                    withClue(path) {
                        val busy = post(path, body)
                        busy.response.status shouldBe 503
                        busy.body() shouldBe """{"error":"ADMIN_WRITE_BUSY"}"""
                    }
                }
                post(MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.decision(a.approvalId)).response.status shouldBe 200
            } finally {
                release.countDown()
                pool.shutdown()
                pool.awaitTermination(10, TimeUnit.SECONDS)
            }
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
        }
    }

    Given("non-exposure in logs and metrics labels (§9.7)") {
        Then("no signing secret, signature header or v1, MASTER idempotency key or evidence reference value in any log line; the mop.<approvalId> correlation id is there (control)") {
            val contractId = contract()
            val a = Approval(evidenceRef = "SECRET-EVIDENCE-REF-0001")
            val body = MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)
            val headers = mutableListOf<String>()
            val lines = CapturedLogs().use { logs ->
                listOf(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.DEADLINE_CONFIRMATIONS).forEach { path ->
                    val header = MasterAdminChannelFixture.springHeader("POST", path, body.toByteArray(Charsets.UTF_8))
                    headers += header
                    post(path, body, header).response.status shouldBe 200
                }
                post(MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.decision(a.approvalId)).response.status shouldBe 200
                logs.lines()
            }
            lines.count { "correlationId=mop.${a.approvalId}" in it } shouldNotBe 0
            val secrets = listOf(MasterAdminChannelFixture.SPRING_SECRET, a.idempotencyKey, a.evidenceRef) + headers + headers.map { it.substringAfter("v1=") }
            secrets.forEach { s -> lines.filter { s in it }.shouldBeEmpty() }
            registry.find(MerchantApprovalController.APPROVAL_REJECTED_METRIC).counters().flatMap { c -> c.id.tags.map { it.value } }.forEach { v ->
                v shouldNotBe a.approvalId
            }
        }
    }
})

private fun succeeded(r: MvcResult): String = r.response.getContentAsString(Charsets.UTF_8).also { check(it.contains("\"SUCCEEDED\"")) { it } }

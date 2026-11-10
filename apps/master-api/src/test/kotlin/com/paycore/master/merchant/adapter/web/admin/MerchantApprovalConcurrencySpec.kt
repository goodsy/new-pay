package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
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

/**
 * MERCHANT-OPS-002 §8.2 B2-2-7 Concurrency Control(실제 MySQL — 서명 채널 + 실제 Use Case, 요청마다 다른 DB 연결, 시작을 latch로 맞춘다 — B1 MerchantOperationsConcurrencySpec과 같은 방식):
 * 같은 Revision을 다른 approvalId 둘이 동시에 → 1 SUCCEEDED, 1 `REVISION_ALREADY_APPROVED`(merchant → contract 잠금 직렬화), 근거 행 2(EXECUTED 1·REJECTED 1), 승인 사실 1.
 * 같은 바이트 동시 → `admin_request` 1행·근거 행 1(다른 쪽은 replayed=true 또는 503 REQUEST_IN_PROGRESS 뒤 재전송 시 재생). 동시 수는 쓰기 permit(2) 안이다.
 */
@MasterDbTest
class MerchantApprovalConcurrencySpec(
    context: WebApplicationContext,
    private val container: MySQLContainer,
    private val onboarding: MerchantOnboardingUseCase,
) : BehaviorSpec({

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

    Given("two approvals racing on the same contract revision") {
        Then("different approvalIds (3 rounds): exactly one SUCCEEDED and one REVISION_ALREADY_APPROVED; one approval fact; evidence rows EXECUTED 1 and REJECTED 1") {
            repeat(3) { round ->
                val contractId = OnboardingFixture.readyMerchant(onboarding, approved = false).contractId.value
                val approvals = listOf(Approval(), Approval())
                val results = OnboardingFixture.concurrently(2) { i ->
                    post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, MerchantApprovalBodies.contractRevisionApproval(contractId, a = approvals[i]))
                }.map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { it.first } shouldBe listOf(200, 200)
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "REVISION_ALREADY_APPROVED")
                    count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", contractId) shouldBe 1L
                    val ids = approvals.map { it.approvalId }
                    count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'EXECUTED'", ids[0], ids[1]) shouldBe 1L
                    count(
                        "SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id IN (?, ?) AND outcome = 'REJECTED' AND result_code = 'REVISION_ALREADY_APPROVED'",
                        ids[0], ids[1],
                    ) shouldBe 1L
                }
            }
        }

        Then("the same bytes twice at once: one admin_request row and one evidence row; the other answer is a replay (or in progress, then a replay on resend)") {
            val contractId = OnboardingFixture.readyMerchant(onboarding, approved = false).contractId.value
            val a = Approval()
            val body = MerchantApprovalBodies.contractRevisionApproval(contractId, a = a)
            val results = OnboardingFixture.concurrently(2) { post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, body) }.map { it.getOrThrow() }
            val settled = results.map { (status, text) ->
                if (status == 503) {
                    text shouldBe """{"error":"REQUEST_IN_PROGRESS"}"""
                    post(MerchantApprovalBodies.CONTRACT_REVISION_APPROVALS, body).second
                } else {
                    text
                }
            }
            settled.map { json.readTree(it).get("outcome").asString() } shouldBe listOf("SUCCEEDED", "SUCCEEDED")
            settled.map { json.readTree(it).get("replayed").asBoolean() } shouldContainExactlyInAnyOrder listOf(false, true)
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", a.idempotencyKey) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_approval_evidence WHERE approval_id = ?", a.approvalId) shouldBe 1L
            count("SELECT COUNT(*) FROM contract_revision_approval WHERE contract_id = ?", contractId) shouldBe 1L
        }
    }
})

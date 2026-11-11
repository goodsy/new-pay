package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.EntitlementCommandFixture
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
 * MERCHANT-OPS-002 §8.3 B3-1-4 Concurrency Control(실제 MySQL — 서명 채널 + 실제 Use Case, 요청마다 다른 DB 연결, latch로 시작을 맞춘다 — B2-2-7과 같은 방식). 3회씩:
 * ① 같은 Activation에 다른 approvalId 둘 → 1 SUCCEEDED·1 `ACTIVATION_TRANSITION_NOT_ALLOWED`, PRIMARY 확인 1행, 근거 EXECUTED 1·REJECTED 1.
 * ② 같은 가맹점의 개통 승인 + Site 심사 승인 → 둘 다 성공(merchant 잠금 직렬화), 권리 revision +2, Snapshot +2.
 * ③ 같은 MID 개통 등록 두 submissionId → 1 `act_…`, 1 `ACTIVATION_DUPLICATE`(거절 reference 키 없음 — C-12).
 * ④ minor-2: 같은 Site에 1인 거절과 2인 승인 → 결정 행 정확히 1(seq 1), 다른 하나 `SITE_REVIEW_TRANSITION_NOT_ALLOWED`, 승인이 졌으면 근거 REJECTED 행.
 */
@MasterDbTest
class ActivationConcurrencySpec(
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
    }
})

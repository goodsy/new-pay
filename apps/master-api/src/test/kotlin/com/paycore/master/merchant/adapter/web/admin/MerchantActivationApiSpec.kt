package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.application.CapturedLogs
import com.paycore.master.merchant.application.EntitlementCommandFixture
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.ChannelType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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

/**
 * MERCHANT-OPS-002 §8.4 B3-1F-3 채널(실제 보안 필터 체인 + MVC + MySQL): 근거 경로 개통 승인·Site 심사 승인 endpoint가 D-MOPS-009 ③ 등록자 거절을
 * 200 `{"outcome":"REJECTED","rejection":"APPROVER_IS_…","replayed":false}`(reference 키 없음 — C-12)로 응답하고, 첫 판정만 지표 `approval_rejected{operation, rejection}`
 * (근거 거절 label 집합 `APPROVAL_REJECTIONS`)을 올리며 경보 후보 WARN 한 줄(`ALERTING_REJECTIONS`)을 남긴다. 같은 바이트 재전송은 `replayed:true`이고 지표를 올리지 않는다.
 */
@MasterDbTest
class MerchantActivationApiSpec(
    context: WebApplicationContext,
    private val registry: MeterRegistry,
    private val onboarding: MerchantOnboardingUseCase,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()

    fun post(path: String, body: String): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return mvc.perform(
            MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)),
        ).andReturn()
    }

    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun rejectedMetric(operation: String, rejection: String) =
        registry.find(MerchantApprovalController.APPROVAL_REJECTED_METRIC).tags("operation", operation, "rejection", rejection).counter()?.count() ?: 0.0

    Given("D-MOPS-009 ③ registrant rejections on the B3-1 evidence endpoints") {
        Then("payment-method-activation-approvals: the activation registrant → 200 REJECTED APPROVER_IS_ACTIVATION_REGISTRANT without a reference; approval_rejected +1 on the first decision only") {
            val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding, activationRegistrant = ApprovalEvidenceFixtures.APPROVER)
            val body = ActivationBodies.activationApproval(ids.second.value, ready.siteId.value, Approval())
            val before = rejectedMetric("ApprovePaymentMethodActivation", "APPROVER_IS_ACTIVATION_REGISTRANT")
            val (first, alerts) = CapturedLogs().use { logs ->
                post(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, body) to logs.lines().filter { it.contains("approval evidence rejected") }
            }
            (first.response.status to first.body()) shouldBe (200 to """{"outcome":"REJECTED","rejection":"APPROVER_IS_ACTIVATION_REGISTRANT","replayed":false}""")
            // 경보 후보(ALERTING_REJECTIONS — ADMIN 검사 우회 신호): WARN 한 줄, 연산·거절 이름만.
            alerts.single() shouldContain "operation=ApprovePaymentMethodActivation"
            alerts.single() shouldContain "rejection=APPROVER_IS_ACTIVATION_REGISTRANT"
            post(ActivationBodies.PAYMENT_METHOD_ACTIVATION_APPROVALS, body).body() shouldBe
                """{"outcome":"REJECTED","rejection":"APPROVER_IS_ACTIVATION_REGISTRANT","replayed":true}"""
            rejectedMetric("ApprovePaymentMethodActivation", "APPROVER_IS_ACTIVATION_REGISTRANT") shouldBe before + 1.0
        }

        Then("site-review-approvals: the site creator → 200 REJECTED APPROVER_IS_SITE_REGISTRANT without a reference; approval_rejected +1 on the first decision only") {
            val (ready, _) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val site = OnboardingFixture.succeeded(
                onboarding.registerMerchantSite(
                    RegisterMerchantSiteCommand(OnboardingFixture.request(actor = ApprovalEvidenceFixtures.APPROVER), ready.merchantId, ChannelType.WEB, OnboardingFixture.host()),
                ),
            )
            val body = ActivationBodies.siteReviewApproval(site, Approval())
            val before = rejectedMetric("DecideSiteReview", "APPROVER_IS_SITE_REGISTRANT")
            val (first, alerts) = CapturedLogs().use { logs ->
                post(ActivationBodies.SITE_REVIEW_APPROVALS, body) to logs.lines().filter { it.contains("approval evidence rejected") }
            }
            (first.response.status to first.body()) shouldBe (200 to """{"outcome":"REJECTED","rejection":"APPROVER_IS_SITE_REGISTRANT","replayed":false}""")
            alerts.single() shouldContain "operation=DecideSiteReview"
            alerts.single() shouldContain "rejection=APPROVER_IS_SITE_REGISTRANT"
            post(ActivationBodies.SITE_REVIEW_APPROVALS, body).body() shouldBe """{"outcome":"REJECTED","rejection":"APPROVER_IS_SITE_REGISTRANT","replayed":true}"""
            rejectedMetric("DecideSiteReview", "APPROVER_IS_SITE_REGISTRANT") shouldBe before + 1.0
        }
    }
})

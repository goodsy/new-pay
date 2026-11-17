package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantApprovalBodies.Approval
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer

/**
 * MERCHANT-CODE-001 MC-1b(§3.7.2) — 실제 보안 필터 체인 + MVC + MySQL: `merchant-code-changes` 서명 채널 성공 200 `mer_…`(`approval_executed{ChangeMerchantCode}` +1, 재생은 +0),
 * 저장된 거절은 §3.3 모양(`reference` 키 없음 — C-12), 형식 위반 400 `REQUEST_MALFORMED`(저장 없음), 결정 조회 DECIDED `operation = ChangeMerchantCode`.
 */
@MasterDbTest
class MerchantCodeChangeApiSpec(
    context: WebApplicationContext,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
    private val onboarding: MerchantOnboardingUseCase,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()

    fun post(path: String, body: String): Pair<Int, String> {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val r = mvc.perform(
            MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)),
        ).andReturn().response
        return r.status to r.getContentAsString(Charsets.UTF_8)
    }
    fun executed() = registry.find(MerchantApprovalController.APPROVAL_EXECUTED_METRIC).tags("operation", "ChangeMerchantCode").counter()?.count() ?: 0.0
    fun merchant(): Pair<String, String> {
        val business = BusinessId(succeeded(OnboardingFixture.registeredBusiness(onboarding, OnboardingFixture.registrationNumber(), "Demo Business")))
        val code = MerchantCodeFixtures.unique().value
        val id = succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName("Demo Merchant"), com.paycore.master.merchant.domain.MerchantCode(code))))
        return id to code
    }
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    Given("the merchant-code-changes endpoint (2 persons — evidence path)") {
        Then("200 SUCCEEDED mer_…; approval_executed +1 only on the first execution; replay is replayed=true; decision lookup names ChangeMerchantCode") {
            val (m, code) = merchant()
            val a = Approval()
            val body = MerchantCodeChangeBodies.body(m, code, MerchantCodeFixtures.unique().value, a = a)
            val before = executed()
            post(MerchantCodeChangeBodies.MERCHANT_CODE_CHANGES, body) shouldBe (200 to """{"outcome":"SUCCEEDED","reference":"$m","replayed":false}""")
            post(MerchantCodeChangeBodies.MERCHANT_CODE_CHANGES, body) shouldBe (200 to """{"outcome":"SUCCEEDED","reference":"$m","replayed":true}""")
            executed() shouldBe before + 1.0
            val (status, decision) = post(MerchantApprovalBodies.APPROVAL_DECISIONS, MerchantApprovalBodies.decision(a.approvalId))
            status shouldBe 200
            decision.contains("\"operation\":\"ChangeMerchantCode\"") shouldBe true
            decision.contains("\"reference\":\"$m\"") shouldBe true
        }
        Then("a stored rejection has no reference key (C-12); a malformed body (registeredBy field) is 400 REQUEST_MALFORMED with nothing stored") {
            val (m, code) = merchant()
            val (_, other) = merchant()
            post(MerchantCodeChangeBodies.MERCHANT_CODE_CHANGES, MerchantCodeChangeBodies.body(m, code, other)) shouldBe
                (200 to """{"outcome":"REJECTED","rejection":"MERCHANT_CODE_TAKEN","replayed":false}""")
            val a = Approval()
            val malformed = MerchantApprovalBodies.with(MerchantCodeChangeBodies.fields(m, code, MerchantCodeFixtures.unique().value, a = a), "registeredBy", "\"admin:adm_01JCRG0000000000000000RG01\"")
            post(MerchantCodeChangeBodies.MERCHANT_CODE_CHANGES, malformed).first shouldBe 400
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", "mop1.${a.approvalId}") shouldBe 0
        }
    }
})

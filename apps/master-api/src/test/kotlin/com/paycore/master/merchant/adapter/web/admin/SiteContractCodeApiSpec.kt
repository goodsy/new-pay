package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.update
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * SITE-CONTRACT-CODE-001 §3.4·§4.1(실제 보안 체인 + MVC + MySQL): 등록 명령 성공 응답 `{outcome, reference, code, replayed}`(Site·계약만 — 가맹점·CSA는 기존 3키),
 * 재생도 같은 code. 상세 `sites[].siteCode`·`contracts[].contractCode` 키는 항상 있고 코드 행이 없으면 `null`(응답 200 — C-15, 키 집합 정확 일치 유지).
 */
@MasterDbTest
class SiteContractCodeApiSpec(
    context: WebApplicationContext,
    private val onboarding: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    fun post(path: String, body: String): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return mvc.perform(
            MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)),
        ).andReturn()
    }
    fun get(path: String): MvcResult = mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path, ByteArray(0)))).andReturn()
    fun MvcResult.node(): JsonNode = json.readTree(response.getContentAsString(Charsets.UTF_8))
    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)

    Given("the signed registration channel") {
        val business = succeeded(OnboardingFixture.registeredBusiness(onboarding, OnboardingFixture.registrationNumber(), "Code Business"))
        val code = MerchantCodeFixtures.unique().value
        val merchantId = succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(), BusinessId(business), DisplayName("Code Merchant"), com.paycore.master.merchant.domain.MerchantCode(code))))

        Then("Site and contract successes answer {outcome, reference, code, replayed}; the replay carries the same code; merchant and CSA keep three keys") {
            val s = Submission()
            val siteBody = MerchantOperationsBodies.registerSite(merchantId, s)
            val site = post(MerchantOperationsBodies.SITES, siteBody)
            site.response.status shouldBe 200
            site.node().propertyNames().toList() shouldBe listOf("outcome", "reference", "code", "replayed")
            site.node().get("code").asString() shouldBe "site-$code-01"
            val siteId = site.node().get("reference").asString()
            post(MerchantOperationsBodies.SITES, siteBody).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$siteId","code":"site-$code-01","replayed":true}"""

            val c = Submission()
            val contractBody = MerchantOperationsBodies.registerContract(business, merchantId, s = c)
            val contract = post(MerchantOperationsBodies.CONTRACTS, contractBody)
            contract.node().propertyNames().toList() shouldBe listOf("outcome", "reference", "code", "replayed")
            val contractId = contract.node().get("reference").asString()
            contract.node().get("code").asString() shouldBe "ctr-$code-01"
            post(MerchantOperationsBodies.CONTRACTS, contractBody).body() shouldBe """{"outcome":"SUCCEEDED","reference":"$contractId","code":"ctr-$code-01","replayed":true}"""

            post(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement(contractId)).node().propertyNames().toList() shouldBe listOf("outcome", "reference", "replayed")
            post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(business)).node().propertyNames().toList() shouldBe listOf("outcome", "reference", "replayed")
        }

        Then("C-15 detail: siteCode / contractCode keys are always present; a missing code row answers null (200), present rows answer the code") {
            val siteA = post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(merchantId)).node().get("reference").asString()
            MasterDbAccounts.root(container).use { it.update("DELETE FROM merchant_site_code WHERE merchant_site_id = ?", siteA) } shouldBe 1
            val detail = get("/internal/v1/admin/merchants/$merchantId")
            detail.response.status shouldBe 200
            val sites = detail.node().get("sites")
            (0 until sites.size()).map { sites.get(it) }.forEach { it.propertyNames().contains("siteCode") shouldBe true }
            (0 until sites.size()).map { sites.get(it) }.associate { it.get("merchantSiteId").asString() to it.get("siteCode").let { n -> if (n.isNull) null else n.asString() } }[siteA] shouldBe null
            (0 until sites.size()).map { sites.get(it).get("siteCode") }.filterNot { it.isNull }.map { it.asString() }.toSet() shouldBe setOf("site-$code-01")
            val contracts = detail.node().get("contracts")
            (0 until contracts.size()).map { contracts.get(it).get("contractCode").asString() } shouldBe listOf("ctr-$code-01")
        }
    }
})

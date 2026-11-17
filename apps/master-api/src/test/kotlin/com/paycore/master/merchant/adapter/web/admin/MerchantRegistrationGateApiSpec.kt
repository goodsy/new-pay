package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.scalar
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper

/**
 * BUSINESS-PROFILE-001 BIZ-4-1(C-27 전환 — 서명 채널 HTTP): DRAFT 사업자로 가맹점 등록 → 200 REJECTED `BUSINESS_NOT_REGISTERED`(reference 없음 — C-12 유지),
 * REGISTERED 사업자 → SUCCEEDED(대조). 옛 사업자 등록 경로 `POST /internal/v1/admin/merchant-operations/businesses` 제거(C-27): 서명된 호출도 404이고 `admin_request` 행이 없으며
 * handler 목록에 없다(새 생성은 `/internal/v1/admin/businesses/commands/create` — BIZ-2).
 */
@MasterDbTest
class MerchantRegistrationGateApiSpec(
    context: WebApplicationContext,
    private val mappings: List<RequestMappingHandlerMapping>,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val legacyPath = "/internal/v1/admin/merchant-operations/businesses"

    fun post(path: String, body: String): MvcResult = mvc.perform(
        MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body.toByteArray(Charsets.UTF_8))
            .header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, body.toByteArray(Charsets.UTF_8))),
    ).andReturn()

    fun MvcResult.body(): String = response.getContentAsString(Charsets.UTF_8)
    fun count(sql: String, vararg args: Any?): Long = OnboardingFixture.runtime(container).use { it.scalar(sql, *args).toString().toLong() }

    fun create(status: String, content: Map<String, Any?>): String {
        val r = post(BusinessBodies.CREATE, BusinessBodies.create(status = status, number = OnboardingFixture.registrationNumber().value, content = content))
        r.response.status shouldBe 200
        return json.readTree(r.body()).get("reference").asString()
    }

    Given("BIZ-4-1 merchant registration through the signed channel after the C-27 transition") {
        Then("D-BIZ-001: a DRAFT business → 200 REJECTED BUSINESS_NOT_REGISTERED without reference; a REGISTERED business → SUCCEEDED (control)") {
            val draft = create("DRAFT", BusinessBodies.draftMinimal())
            val rejected = post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(draft))
            rejected.response.status shouldBe 200
            rejected.body() shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_NOT_REGISTERED","replayed":false}"""
            count("SELECT COUNT(*) FROM merchant WHERE business_id = ?", draft) shouldBe 0L

            val registered = create("REGISTERED", BusinessBodies.individual())
            val ok = post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(registered))
            json.readTree(ok.body()).get("outcome").asString() shouldBe "SUCCEEDED"
        }

        Then("C-27: the legacy business registration route is gone — a signed call is 404 with no admin_request row, and no handler maps the path") {
            val before = count("SELECT COUNT(*) FROM admin_request")
            val body = """{"idempotencyKey":"mo1.01JC00000000000000000000V1.${java.util.UUID.randomUUID().toString().replace("-", "")}",""" +
                """"actor":"admin:${MerchantOperationsBodies.ACCOUNT}","correlationId":"mos.legacy","registrationNumber":"${OnboardingFixture.registrationNumber().value}","displayName":"옛 경로"}"""
            post(legacyPath, body).response.status shouldBe 404
            count("SELECT COUNT(*) FROM admin_request") shouldBe before
            mappings.flatMap { it.handlerMethods.keys }.flatMap { it.patternValues } shouldNotContain legacyPath
        }
    }
})

package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.persistence.MasterSchemaRows
import com.paycore.master.merchant.application.OnboardingFixture
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.MerchantCodeFixtures
import com.paycore.master.update
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * MERCHANT-CODE-001 MC-1-6(§3.8 — 실제 보안 체인 + MVC + MySQL): 목록·기존 검색 항목 10키(`matchedRetiredCode` 없음), 코드 검색 11키(현재 코드 → false, 은퇴 코드 → true),
 * 코드 + 다른 필터 400, 대문자 400, 상세 세 키(`merchant.merchantCode`·`merchantCodeHistory`·`merchantCodeChangeable`)·`registeredBy` 없음.
 * MC-1-9 보강: 실제 응답의 키 구조(재귀 키 집합)가 golden `merchant-ops-response-shapes-v2.golden.json`의 같은 항목과 같다(ADMIN이 해석하는 모양 = MASTER가 실제로 내는 모양).
 * 은퇴 코드 Fixture: 1회 변경 경로(MC-1b)가 아직 없으므로 V13 CHECK를 만족하는 seq 2 행을 root로 넣는다(유효한 저장 상태 — 시험 데이터 준비).
 */
@MasterDbTest
class MerchantCodeApiSpec(
    context: WebApplicationContext,
    private val onboarding: MerchantOnboardingUseCase,
    private val container: MySQLContainer,
) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val golden: JsonNode = json.readTree(MerchantCodeApiSpec::class.java.getResource("/contracts/merchant-ops-response-shapes-v2.golden.json")!!.readText(Charsets.UTF_8))
    val search = "/internal/v1/admin/merchant-searches"

    fun get(path: String): MvcResult = mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path, ByteArray(0)))).andReturn()
    fun post(path: String, body: String): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return mvc.perform(
            MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)),
        ).andReturn()
    }
    fun MvcResult.node(): JsonNode = json.readTree(response.getContentAsString(Charsets.UTF_8))

    /** 재귀 키 구조(객체 = 키 집합 + 각 값의 구조, 배열 = 첫 원소 구조, 값 = "*"). */
    fun shape(node: JsonNode?): Any = when {
        node == null || node.isNull -> "*"
        node.isObject -> node.propertyNames().toSet().associateWith { shape(node.get(it)) }
        node.isArray -> if (node.isEmpty) "[]" else listOf(shape(node.get(0)))
        else -> "*"
    }
    fun keys(node: JsonNode): Set<String> = node.propertyNames().toSet()

    val listKeys = setOf(
        "merchantId", "merchantName", "businessId", "businessName", "registrationNumber", "lifecycle", "entitlementRevision", "midCount", "createdAt", "merchantCode",
    )

    Given("MC-1-6 a merchant registered with a code, then (as MC-1b would) changed once") {
        val business = succeeded(OnboardingFixture.registeredBusiness(onboarding, OnboardingFixture.registrationNumber(), "Code Business"))
        val retired = MerchantCodeFixtures.unique()
        val current = MerchantCodeFixtures.unique()
        val merchantId = succeeded(onboarding.registerMerchant(RegisterMerchantCommand(request(), BusinessId(business), DisplayName("Code Merchant"), retired)))
        val listedBeforeChange = post(search, """{"businessId":"$business"}""").node()
        val detailBeforeChange = get("/internal/v1/admin/merchants/$merchantId").node()
        MasterDbAccounts.root(container).use { c ->
            c.update(
                "INSERT INTO merchant_code_assignment (merchant_id, assignment_seq, merchant_code, replaces_code, source, approval_id, reason_code, assigned_at, assigned_by) " +
                    "VALUES (?, 2, ?, ?, 'CHANGE', ?, 'TYPO_CORRECTION', '2026-10-09 03:00:00.000', 'admin:adm_01JC00000000000000000000V2')",
                merchantId, current.value, retired.value, MasterSchemaRows.id("apr"),
            )
        }

        Then("MC-1-6 list / existing search: 10 item keys with the code at that time and no matchedRetiredCode; the key structure equals the golden list") {
            val item = listedBeforeChange.get("items").single()
            keys(item) shouldBe listKeys
            item.get("merchantCode").asString() shouldBe retired.value
            shape(listedBeforeChange) shouldBe shape(golden.get("list"))
            val after = post(search, """{"businessId":"$business","limit":1}""").node().get("items").single()
            keys(after) shouldBe listKeys
            after.get("merchantCode").asString() shouldBe current.value
        }

        Then("MC-1-6 code search: the current code -> 11 keys, matchedRetiredCode false; the retired code -> the same merchant, matchedRetiredCode true; the structure equals the golden codeSearch") {
            val byCurrent = post(search, """{"merchantCode":"${current.value}"}""")
            byCurrent.response.status shouldBe 200
            val currentItem = byCurrent.node().get("items").single()
            keys(currentItem) shouldBe listKeys + "matchedRetiredCode"
            listOf(currentItem.get("merchantId").asString(), currentItem.get("merchantCode").asString(), currentItem.get("matchedRetiredCode").asBoolean()) shouldBe
                listOf(merchantId, current.value, false)
            val byRetired = post(search, """{"merchantCode":"${retired.value}"}""").node()
            val retiredItem = byRetired.get("items").single()
            listOf(retiredItem.get("merchantId").asString(), retiredItem.get("merchantCode").asString(), retiredItem.get("matchedRetiredCode").asBoolean()) shouldBe
                listOf(merchantId, current.value, true)
            shape(byRetired) shouldBe shape(golden.get("codeSearch"))
            post(search, """{"merchantCode":"${MerchantCodeFixtures.unique().value}"}""").response.getContentAsString(Charsets.UTF_8) shouldBe """{"items":[],"nextCursor":null}"""
        }

        Then("MC-1-6 code with another filter, upper case or another malformed code is 400 REQUEST_MALFORMED") {
            listOf(
                """{"merchantCode":"${current.value}","businessId":"$business"}""",
                """{"merchantCode":"${current.value}","lifecycle":"ACTIVE"}""",
                """{"merchantCode":"${current.value.uppercase()}"}""",
                """{"merchantCode":"shop-abcd12"}""",
            ).forEach { body ->
                withClue(body) {
                    val res = post(search, body)
                    res.response.status shouldBe 400
                    res.response.getContentAsString(Charsets.UTF_8) shouldBe """{"error":"REQUEST_MALFORMED"}"""
                }
            }
        }

        Then("MC-1-6 detail: merchant.merchantCode is the current code, history has both rows (no approval id), changeable is false after the change; no registeredBy on the merchant") {
            val node = get("/internal/v1/admin/merchants/$merchantId").node()
            keys(node) shouldBe setOf("merchant", "business", "sites", "contracts", "accounts", "limits", "propagation", "merchantCodeHistory", "merchantCodeChangeable")
            node.get("merchant").propertyNames().toList() shouldBe listOf("merchantId", "name", "lifecycle", "entitlementRevision", "createdAt", "merchantCode")
            node.get("merchant").get("merchantCode").asString() shouldBe current.value
            val history = node.get("merchantCodeHistory")
            (0 until history.size()).map { i -> history.get(i).let { listOf(it.get("seq").asInt().toString(), it.get("code").asString(), it.get("source").asString()) } } shouldBe
                listOf(listOf("1", retired.value, "REGISTRATION"), listOf("2", current.value, "CHANGE"))
            history.get(0).propertyNames().toList() shouldBe listOf("code", "seq", "source", "replacesCode", "assignedAt", "assignedBy", "reasonCode")
            history.get(1).get("replacesCode").asString() shouldBe retired.value
            node.get("merchantCodeChangeable").asBoolean() shouldBe false
            node.toString().contains("apr_") shouldBe false
            shape(node.get("merchantCodeHistory")) shouldBe shape(golden.get("detail").get("merchantCodeHistory"))
            shape(node.get("merchant")) shouldBe shape(golden.get("detail").get("merchant"))
        }

        Then("MC-1-6 control: before the change the merchant was changeable (active, no seq 2, no approved contract) with one REGISTRATION row") {
            detailBeforeChange.get("merchantCodeChangeable").asBoolean() shouldBe true
            detailBeforeChange.get("merchantCodeHistory").size() shouldBe 1
            detailBeforeChange.get("merchantCodeHistory").get(0).get("replacesCode").isNull shouldBe true
        }
    }

    Given("MC-1-6 a merchant whose contract revision is approved") {
        val ready = OnboardingFixture.readyMerchant(onboarding)
        Then("MC-1-6 merchantCodeChangeable is false (the approval fact exists) even without a change") {
            val node = get("/internal/v1/admin/merchants/${ready.merchantId.value}").node()
            node.get("merchantCodeHistory").size() shouldBe 1
            node.get("merchantCodeChangeable").asBoolean() shouldBe false
        }
    }
})

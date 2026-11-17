package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.web.admin.MerchantOperationsBodies.Submission
import com.paycore.master.merchant.application.OnboardingFixture
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
 * MERCHANT-OPS-002 §7 Concurrency Control·§8.1 B1-1-3(실제 MySQL — 서명 채널 + 실제 Use Case, 요청마다 다른 DB 연결, 시작을 `CountDownLatch`로 맞춘다):
 * 같은 등록번호·다른 키 동시 → 1 성공 1 `BUSINESS_REGISTRATION_NUMBER_DUPLICATE`, 같은 채널 동시 → 1 성공 1 `SITE_CHANNEL_DUPLICATE`, 같은 키·같은 본문 동시 → 업무 행 1개·`admin_request` 1행
 * (다른 쪽은 `replayed=true` 또는 503 `REQUEST_IN_PROGRESS` 뒤 재전송 시 재생 — m-4). 동시 수는 시험 설정 쓰기 permit(2) 안이다.
 * 기대값 변경(BUSINESS-PROFILE-001 BIZ-4 C-27): 옛 `merchant-operations/businesses`가 제거돼 사업자 경합 두 시험은 새 생성 명령(`businesses/commands/create` REGISTERED)으로 같은 단언을 한다
 * (중복 거절은 D-BIZ-003으로 `existing`이 붙지만 `rejection` code는 같다). 가맹점 준비용 사업자도 새 경로(REGISTERED — D-BIZ-001).
 */
@MasterDbTest
class MerchantOperationsConcurrencySpec(context: WebApplicationContext, private val container: MySQLContainer) : BehaviorSpec({

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()

    /** 서명 POST → (HTTP 상태, 본문). */
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
    fun reference(body: String): String = json.readTree(body).get("reference").asString()

    Given("two operators racing on the same business key") {
        Then("same registration number, different keys (3 rounds): exactly one SUCCEEDED and one BUSINESS_REGISTRATION_NUMBER_DUPLICATE, one business row") {
            repeat(3) { round ->
                val brn = OnboardingFixture.registrationNumber().value
                val results = OnboardingFixture.concurrently(2) { post(BusinessBodies.CREATE, BusinessBodies.create(Submission(), number = brn)) }
                    .map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { it.first } shouldBe listOf(200, 200)
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "BUSINESS_REGISTRATION_NUMBER_DUPLICATE")
                    count("SELECT COUNT(*) FROM business WHERE registration_number = ?", brn) shouldBe 1L
                }
            }
        }

        Then("same channel on two merchants: exactly one SUCCEEDED and one SITE_CHANNEL_DUPLICATE, one active site row") {
            fun merchant(): String {
                val biz = reference(post(BusinessBodies.CREATE, BusinessBodies.create(number = OnboardingFixture.registrationNumber().value)).second)
                return reference(post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(biz)).second)
            }
            val merchants = listOf(merchant(), merchant())
            val host = OnboardingFixture.host()
            val results = OnboardingFixture.concurrently(2) { i -> post(MerchantOperationsBodies.SITES, MerchantOperationsBodies.registerSite(merchants[i], identifier = host)) }
                .map { it.getOrThrow() }
            results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "SITE_CHANNEL_DUPLICATE")
            count("SELECT COUNT(*) FROM merchant_site WHERE channel_identifier = ?", host) shouldBe 1L
        }

        Then("m-4: the same key and body twice at once leave one business row and one admin_request row; the other answer is a replay (or in-progress, then a replay on resend)") {
            val s = Submission()
            val brn = OnboardingFixture.registrationNumber().value
            val body = BusinessBodies.create(s, number = brn)
            val results = OnboardingFixture.concurrently(2) { post(BusinessBodies.CREATE, body) }.map { it.getOrThrow() }
            val settled = results.map { (status, text) ->
                if (status == 503) {
                    text shouldBe """{"error":"REQUEST_IN_PROGRESS"}"""
                    post(BusinessBodies.CREATE, body).also { it.first shouldBe 200 }.second
                } else {
                    status shouldBe 200
                    text
                }
            }
            settled.map { json.readTree(it).get("outcome").asString() } shouldBe listOf("SUCCEEDED", "SUCCEEDED")
            settled.map { reference(it) }.toSet().size shouldBe 1
            settled.count { json.readTree(it).get("replayed").asBoolean() } shouldBe 1
            count("SELECT COUNT(*) FROM business WHERE registration_number = ?", brn) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L
        }
    }

    Given("B1-2: two operators racing on the same contract (§8.1 B1-2-1 — same matrix as B1-1)") {
        fun merchantOf(biz: String): String = reference(post(MerchantOperationsBodies.MERCHANTS, MerchantOperationsBodies.registerMerchant(biz)).second)
        fun business(): String = reference(post(BusinessBodies.CREATE, BusinessBodies.create(number = OnboardingFixture.registrationNumber().value)).second)

        /** 같은 키·같은 본문 동시 2건 → 503 REQUEST_IN_PROGRESS면 같은 본문 재전송으로 결과를 받는다. */
        fun settle(path: String, body: String, results: List<Pair<Int, String>>): List<String> = results.map { (status, text) ->
            if (status == 503) {
                text shouldBe """{"error":"REQUEST_IN_PROGRESS"}"""
                post(path, body).also { it.first shouldBe 200 }.second
            } else {
                status shouldBe 200
                text
            }
        }

        Then("m-4: the same key and contract body twice at once leave one contract row and one admin_request row; the other answer is a replay") {
            val biz = business()
            val mer = merchantOf(biz)
            val s = Submission()
            val body = MerchantOperationsBodies.registerContract(biz, mer, s = s)
            val settled = settle(MerchantOperationsBodies.CONTRACTS, body, OnboardingFixture.concurrently(2) { post(MerchantOperationsBodies.CONTRACTS, body) }.map { it.getOrThrow() })
            settled.map { json.readTree(it).get("outcome").asString() } shouldBe listOf("SUCCEEDED", "SUCCEEDED")
            settled.map { reference(it) }.toSet().size shouldBe 1
            settled.count { json.readTree(it).get("replayed").asBoolean() } shouldBe 1
            count("SELECT COUNT(*) FROM contract WHERE merchant_id = ?", mer) shouldBe 1L
            count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", s.idempotencyKey) shouldBe 1L
        }

        Then("the same service kind on one contract with different keys at once (3 rounds): exactly one SUCCEEDED and one SERVICE_KIND_DUPLICATE, one agreement row") {
            repeat(3) { round ->
                val biz = business()
                val contract = reference(post(MerchantOperationsBodies.CONTRACTS, MerchantOperationsBodies.registerContract(biz, merchantOf(biz))).second)
                val results = OnboardingFixture.concurrently(2) { post(MerchantOperationsBodies.AGREEMENTS, MerchantOperationsBodies.addServiceAgreement(contract)) }
                    .map { it.getOrThrow() }
                withClue("round $round") {
                    results.map { it.first } shouldBe listOf(200, 200)
                    results.map { outcome(it.second) } shouldContainExactlyInAnyOrder listOf("SUCCEEDED", "SERVICE_KIND_DUPLICATE")
                    count("SELECT COUNT(*) FROM contract_service_agreement WHERE contract_id = ?", contract) shouldBe 1L
                }
            }
        }
    }
})

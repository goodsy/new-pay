package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.BusinessCommandFixture
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * MERCHANT-FLOW-001 FLOW-2c(§4B.7 — MASTER 서명 채널, 실제 MySQL): `POST /internal/v1/admin/businesses/business-contracts/commands/{suspend,resume,terminate}`.
 * 본문 key 전부 필수: 공통 + businessId·contractSeq(≥ 1)·expectedEventCount(≥ 0)·reason(필수 문자열)·evidenceRef(문자열|null). 응답 틀은 접수 건 명령과 같다.
 * 400: 구조 `REQUEST_MALFORMED`, 값 `BUSINESS_VALIDATION_FAILED`(reason REQUIRED·CARD_NUMBER_PATTERN, evidenceRef FORMAT·CARD_NUMBER_PATTERN — 값 없음). 서명 없음 401.
 */
@MasterDbTest
class BusinessContractCommandApiSpec(
    context: WebApplicationContext,
    private val businesses: BusinessProfileCommandUseCase,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    afterEach { clock.reset() }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val intakeBase = "/internal/v1/admin/businesses/contract-intakes/commands"
    val base = "/internal/v1/admin/businesses/business-contracts/commands"
    val actor = "admin:adm_01JCFL0000000000000000OP01"

    fun post(path: String, body: String, signed: Boolean = true): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
        return mvc.perform(if (signed) b.header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)) else b).andReturn()
    }

    fun body(r: MvcResult) = r.response.getContentAsString(Charsets.UTF_8)
    fun key() = "bc-" + UUID.randomUUID().toString().take(18)
    fun common(k: String, id: String) = """"idempotencyKey":"$k","actor":"$actor","correlationId":"corr-$k","businessId":"$id""""
    fun event(k: String, id: String, count: Int, reason: String? = "확인 필요", evidence: String? = null, seq: Int = 1) =
        """{${common(k, id)},"contractSeq":$seq,"expectedEventCount":$count,"reason":${reason?.let { "\"$it\"" } ?: "null"},"evidenceRef":${evidence?.let { "\"$it\"" } ?: "null"}}"""

    /** 신규 접수 → 계약서 접수 → 계약 완료(서명 채널 — 공식 경로). */
    fun contracted(): String {
        val id = BusinessCommandFixture.withoutMerchants(businesses).businessId.value
        post("$intakeBase/open", """{${common(key(), id)},"expectedKind":"NEW","receivedOn":"2026-11-26","intakeChannel":"PHONE","contractManager":"$actor","desiredServices":[],"reason":null}""")
        post("$intakeBase/move", """{${common(key(), id)},"intakeSeq":1,"expectedVersion":0,"target":"DOCUMENT_RECEIVED","reason":null}""")
        post(
            "$intakeBase/complete",
            """{${common(key(), id)},"intakeSeq":1,"expectedVersion":1,"documentRef":"DOC-1","startDate":"2026-11-26","endDate":null,"services":["CARD_PG_PAYMENT"],"reason":null}""",
        ).response.status shouldBe 200
        return id
    }

    Given("FLOW-2c signed command channel") {
        Then("suspend → resume → terminate succeed (reference = businessId); stored rejection 200 REJECTED; same key replay; other content 409; the section shows TERMINATED") {
            clock.set(Instant.parse("2026-11-26T01:00:00Z"))
            val id = contracted()
            listOf(
                post("$base/suspend", event(key(), id, 0, evidence = "CASE/2026-11:77")), post("$base/resume", event(key(), id, 1)),
                post("$base/terminate", event(key(), id, 2)),
            ).forEach { listOf(it.response.status, body(it)) shouldBe listOf(200, """{"outcome":"SUCCEEDED","reference":"$id","replayed":false}""") }
            val k = key()
            post("$base/suspend", event(k, id, 3)).let { body(it) } shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED","replayed":false}"""
            post("$base/suspend", event(k, id, 3)).let { body(it) } shouldBe """{"outcome":"REJECTED","rejection":"BUSINESS_CONTRACT_TRANSITION_NOT_ALLOWED","replayed":true}"""
            post("$base/suspend", event(k, id, 3, reason = "다른 사유")).let { listOf(it.response.status, body(it)) } shouldBe listOf(409, """{"error":"IDEMPOTENCY_KEY_REUSED"}""")
            val path = BusinessBodies.contractIntakes(id)
            val section = mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path))).andReturn()
            val c = json.readTree(body(section)).get("businessContract")
            listOf(c.get("status").asString(), c.get("events").size(), c.get("events").get(0).get("evidenceRef").asString()) shouldBe listOf("TERMINATED", 3, "CASE/2026-11:77")
            body(section) shouldNotContain "확인 필요" // 사유 원문은 섹션에 없다(이력 탭)
        }

        Then("400 structure / values (no echo) and 401 unsigned") {
            val id = BusinessCommandFixture.withoutMerchants(businesses).businessId.value
            listOf(
                """{${common(key(), id)},"contractSeq":1,"expectedEventCount":0,"reason":"x"}""",
                event(key(), id, -1),
                event(key(), id, 0, seq = 0),
                event(key(), "biz_short", 0),
            ).forEach { b -> post("$base/terminate", b).let { listOf(it.response.status, body(it)) } shouldBe listOf(400, """{"error":"REQUEST_MALFORMED"}""") }
            fun errors(r: MvcResult): List<String> {
                val t = json.readTree(body(r))
                val f = t.get("fieldErrors")
                return listOf("${r.response.status}", t.get("error").asString()) + (0 until (f?.size() ?: 0)).map { "${f.get(it).get("path").asString()}:${f.get(it).get("code").asString()}" }
            }
            errors(post("$base/suspend", event(key(), id, 0, reason = null))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "reason:REQUIRED")
            errors(post("$base/suspend", event(key(), id, 0, reason = "card 4111 1111 1111 1111"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "reason:CARD_NUMBER_PATTERN")
            errors(post("$base/resume", event(key(), id, 0, evidence = "bad ref"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "evidenceRef:FORMAT")
            post("$base/terminate", event(key(), id, 0, evidence = "4111-1111-1111-1111")).let { listOf(it.response.status, body(it).contains("4111")) } shouldBe listOf(400, false)
            post("$base/terminate", event(key(), id, 0), signed = false).response.status shouldBe 401
        }
    }
})

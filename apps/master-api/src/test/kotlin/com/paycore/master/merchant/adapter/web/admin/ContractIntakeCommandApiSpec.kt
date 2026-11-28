package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.BusinessCommandFixture
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.merchant.application.provided.BusinessProfileCommandUseCase
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
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
 * MERCHANT-FLOW-001 FLOW-2b(§4B.7·§4B.11 — MASTER 서명 채널, 실제 MySQL): `POST /internal/v1/admin/businesses/contract-intakes/commands/{open,move,complete}` +
 * `GET …/{id}/contract-intakes`(golden v2 모양의 사업자 계약 칸).
 * - 200 SUCCEEDED(reference = businessId)·200 REJECTED(저장 거절 code, reference 없음)·같은 키 재생 `replayed`·같은 키 다른 내용 409.
 * - 400 `REQUEST_MALFORMED`(모르는/빠진 key·자료형·열거)·400 `BUSINESS_VALIDATION_FAILED` + fieldErrors(path·code만 — '영업대행사' 경로 NOT_ALLOWED, 카드번호 패턴, 근거 형식).
 * - 서명 없음 401. 응답에 '사유 및 비고'·근거 원문이 메아리치지 않는다.
 */
@MasterDbTest
class ContractIntakeCommandApiSpec(
    context: WebApplicationContext,
    private val businesses: BusinessProfileCommandUseCase,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    afterEach { clock.reset() }

    val mvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity()).build()
    val json = JsonMapper.builder().build()
    val base = "/internal/v1/admin/businesses/contract-intakes/commands"
    val actor = "admin:adm_01JCFL0000000000000000OP01"
    val reasonCanary = "캔어리비고사유"

    fun post(path: String, body: String, signed: Boolean = true): MvcResult {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val b = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
        return mvc.perform(if (signed) b.header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("POST", path, bytes)) else b).andReturn()
    }

    fun body(r: MvcResult) = r.response.getContentAsString(Charsets.UTF_8)
    fun common(key: String, businessId: String) = """"idempotencyKey":"$key","actor":"$actor","correlationId":"corr-$key","businessId":"$businessId""""
    fun key() = "ci-" + UUID.randomUUID().toString().take(18)

    fun openBody(key: String, id: String, kind: String = "NEW", channel: String = "PHONE", reason: String? = null, receivedOn: String = "2026-11-26", manager: String = actor) =
        """{${common(key, id)},"expectedKind":"$kind","receivedOn":"$receivedOn","intakeChannel":"$channel","contractManager":"$manager",""" +
            """"desiredServices":["CARD_AUTH"],"reason":${reason?.let { "\"$it\"" } ?: "null"}}"""

    fun moveBody(key: String, id: String, seq: Int, version: Long, target: String, reason: String? = null) =
        """{${common(key, id)},"intakeSeq":$seq,"expectedVersion":$version,"target":"$target","reason":${reason?.let { "\"$it\"" } ?: "null"}}"""

    fun completeBody(key: String, id: String, seq: Int, version: Long, document: String = "DOC-2026-11-0001", start: String = "2026-11-26", end: String? = null) =
        """{${common(key, id)},"intakeSeq":$seq,"expectedVersion":$version,"documentRef":"$document","startDate":"$start","endDate":${end?.let { "\"$it\"" } ?: "null"},""" +
            """"services":["CARD_PG_PAYMENT"],"reason":null}"""

    fun business(): String = BusinessCommandFixture.withoutMerchants(businesses).businessId.value

    /** FLOW-2d 접수 항목 수정 본문(열린 건의 접수일·경로·담당자·희망 서비스 + 사유). */
    fun itemsBody(
        key: String, id: String, seq: Int, version: Long, receivedOn: String = "2026-11-26", channel: String = "WEBSITE", manager: String = actor,
        desired: String = "\"CARD_AUTH\",\"CARD_KEYIN\"", reason: String? = null,
    ) = """{${common(key, id)},"intakeSeq":$seq,"expectedVersion":$version,"receivedOn":"$receivedOn","intakeChannel":"$channel","contractManager":"$manager",""" +
        """"desiredServices":[$desired],"reason":${reason?.let { "\"$it\"" } ?: "null"}}"""

    Given("FLOW-2b signed command channel") {
        Then("open → move → complete succeed (reference = businessId); the section then shows business contract #1 ACTIVE with version 1 and the intake kind NEW") {
            clock.set(Instant.parse("2026-11-26T01:00:00Z"))
            val id = business()
            listOf(
                post("$base/open", openBody(key(), id, reason = reasonCanary)),
                post("$base/move", moveBody(key(), id, 1, 0, "DOCUMENT_RECEIVED")),
                post("$base/complete", completeBody(key(), id, 1, 1, end = "2027-11-25")),
            ).forEach { r ->
                listOf(r.response.status, body(r)) shouldBe listOf(200, """{"outcome":"SUCCEEDED","reference":"$id","replayed":false}""")
            }
            val path = BusinessBodies.contractIntakes(id)
            val section = mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path))).andReturn()
            section.response.status shouldBe 200
            body(section) shouldNotContain reasonCanary
            val tree = json.readTree(body(section))
            val c = tree.get("businessContract")
            listOf(c.get("contractSeq").asInt(), c.get("status").asString(), c.get("applyingVersionNo").asInt(), c.get("versions").size(), c.get("linkedMerchantCount").asInt()) shouldBe
                listOf(1, "ACTIVE", 1, 1, 0)
            c.get("versions").get(0).let { listOf(it.get("startDate").asString(), it.get("endDate").asString(), it.get("documentRef").asString(), it.get("createdAt").asString()) } shouldBe
                listOf("2026-11-26", "2027-11-25", "DOC-2026-11-0001", "2026-11-26T01:00:00.000Z")
            tree.get("intakes").get(0).let { listOf(it.get("kind").asString(), it.get("status").asString(), it.get("targetContractSeq").isNull) } shouldBe
                listOf("NEW", "COMPLETED", true)
            tree.get("judgedAt").asString() shouldBe "2026-11-26T01:00:00.000Z"
        }

        Then("FLOW-2d update-items: 200 SUCCEEDED (reference = businessId) and the section shows the new items; a stale version is a stored REJECTED; 400 SALES_AGENCY / manager format / structure") {
            clock.set(Instant.parse("2026-11-26T01:00:00Z"))
            val id = business()
            post("$base/open", openBody(key(), id)).response.status shouldBe 200
            val ok = post("$base/update-items", itemsBody(key(), id, 1, 0))
            listOf(ok.response.status, body(ok)) shouldBe listOf(200, """{"outcome":"SUCCEEDED","reference":"$id","replayed":false}""")
            val path = BusinessBodies.contractIntakes(id)
            val section = json.readTree(body(mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path))).andReturn()))
            section.get("intakes").get(0).let { listOf(it.get("intakeChannel").asString(), it.get("desiredServices").size(), it.get("version").asInt()) } shouldBe listOf("WEBSITE", 2, 1)
            val stale = post("$base/update-items", itemsBody(key(), id, 1, 0, channel = "OTHER"))
            body(stale) shouldBe """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_VERSION_CONFLICT","replayed":false}"""
            fun fieldErrors(r: MvcResult): List<String> {
                val t = json.readTree(body(r))
                return listOf("${r.response.status}", t.get("error")?.asString() ?: body(r)) +
                    (t.get("fieldErrors")?.let { f -> (0 until f.size()).map { i -> "${f.get(i).get("path").asString()}:${f.get(i).get("code").asString()}" } } ?: emptyList())
            }
            fieldErrors(post("$base/update-items", itemsBody(key(), id, 1, 1, channel = "SALES_AGENCY"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "intakeChannel:NOT_ALLOWED")
            fieldErrors(post("$base/update-items", itemsBody(key(), id, 1, 1, manager = "system:demo-seed"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "contractManager:FORMAT")
            fieldErrors(post("$base/update-items", itemsBody(key(), id, 1, 1, reason = "card 4111 1111 1111 1111"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "reason:CARD_NUMBER_PATTERN")
            fieldErrors(post("$base/update-items", itemsBody(key(), id, 1, 1, receivedOn = "2026-02-30"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "receivedOn:FORMAT")
            listOf(itemsBody(key(), id, 0, 1), itemsBody(key(), id, 1, -1), """{${common(key(), id)},"intakeSeq":1,"expectedVersion":1}""").forEach { b ->
                listOf(post("$base/update-items", b).response.status, body(post("$base/update-items", b))) shouldBe listOf(400, """{"error":"REQUEST_MALFORMED"}""")
            }
            post("$base/update-items", itemsBody(key(), id, 1, 1), signed = false).response.status shouldBe 401
        }

        // 리뷰 MINOR-2(Observability): 명령 처리 중 MASTER 로그(ROOT appender — Controller·AdminCommandExecutor·Service 경로)에 사유·근거 원문이 없다 + 양성 대조.
        Then("no MASTER log event carries the '사유 및 비고' or document-reference canary during open / move / complete (positive controls: probe captured, values stored)") {
            clock.set(Instant.parse("2026-11-26T01:00:00Z"))
            val id = business()
            val docCanary = "CANARY-DOC-7731"
            val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            try {
                org.slf4j.LoggerFactory.getLogger("com.paycore.master.observability.probe").info("contract-intake-command-log-probe")
                post("$base/open", openBody(key(), id, reason = reasonCanary)).response.status shouldBe 200
                post("$base/move", moveBody(key(), id, 1, 0, "DOCUMENT_RECEIVED", reason = reasonCanary)).response.status shouldBe 200
                post("$base/complete", completeBody(key(), id, 1, 1, document = docCanary)).response.status shouldBe 200
                // 리뷰 NIT-A: 계약 완료 뒤라 열린 건은 없다 — 이 호출은 '계약 있음'인데 NEW를 보낸 저장된 거절(종류 불일치) 경로다.
                body(post("$base/open", openBody(key(), id, reason = reasonCanary))) shouldBe
                    """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_KIND_MISMATCH","replayed":false}"""
            } finally {
                root.detachAppender(appender)
            }
            val events = appender.list.toList()
            events.any { it.formattedMessage == "contract-intake-command-log-probe" } shouldBe true
            events.any { it.formattedMessage.contains("operation=CompleteContractIntake") } shouldBe true // 명령 경로 로그가 실제로 잡혔다
            fun texts(e: ch.qos.logback.classic.spi.ILoggingEvent): List<String> = buildList {
                add(e.formattedMessage ?: ""); add(e.message ?: "")
                e.argumentArray?.forEach { add(it.toString()) }
                addAll(e.mdcPropertyMap.values)
                var t = e.throwableProxy
                while (t != null) { add(t.message ?: ""); t = t.cause }
            }
            events.flatMap(::texts).filter { it.contains(reasonCanary) || it.contains(docCanary) }.shouldBeEmpty()
            // 양성 대조: 값은 MASTER 표에 원문으로 저장됐다(근거는 섹션에 보인다 — D-FLOW-004 ④).
            val path = BusinessBodies.contractIntakes(id)
            body(mvc.perform(MockMvcRequestBuilders.get(path).header(AdminCallSignature.HEADER, MasterAdminChannelFixture.springHeader("GET", path))).andReturn()) shouldContain docCanary
        }

        Then("stored rejections are 200 REJECTED without reference; the same key replays; the same key with other content is 409") {
            clock.set(Instant.parse("2026-11-26T01:00:00Z"))
            val id = business()
            val k = key()
            post("$base/open", openBody(k, id, kind = "CHANGE")).let { listOf(it.response.status, body(it)) } shouldBe
                listOf(200, """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_KIND_MISMATCH","replayed":false}""")
            post("$base/open", openBody(k, id, kind = "CHANGE")).let { body(it) } shouldBe """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_KIND_MISMATCH","replayed":true}"""
            post("$base/open", openBody(k, id, kind = "NEW")).let { listOf(it.response.status, body(it)) } shouldBe listOf(409, """{"error":"IDEMPOTENCY_KEY_REUSED"}""")
            post("$base/open", openBody(key(), id, receivedOn = "2026-11-27")).let { body(it) } shouldBe
                """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_RECEIVED_ON_FUTURE","replayed":false}"""
        }

        Then("400: malformed structure (no echo) and value errors as fieldErrors (SALES_AGENCY NOT_ALLOWED, card-number pattern, document format); 401 without signature") {
            val id = business()
            listOf(
                """{${common(key(), id)}}""",
                openBody(key(), id, kind = "RENEWAL"),
                openBody(key(), "biz_short"),
                moveBody(key(), id, 0, 0, "DOCUMENT_RECEIVED"),
                moveBody(key(), id, 1, 0, "COMPLETED"), // 계약 완료는 complete 명령만
                completeBody(key(), id, 1, -1),
            ).forEach { b ->
                val r = post(if (b.contains("\"target\"")) "$base/move" else if (b.contains("documentRef")) "$base/complete" else "$base/open", b)
                listOf(r.response.status, body(r)) shouldBe listOf(400, """{"error":"REQUEST_MALFORMED"}""")
            }
            fun fieldErrors(r: MvcResult): List<String> {
                val t = json.readTree(body(r))
                val errors: List<String> = t.get("fieldErrors")?.let { f -> (0 until f.size()).map { i -> "${f.get(i).get("path").asString()}:${f.get(i).get("code").asString()}" } }
                    ?: emptyList()
                return listOf("${r.response.status}", t.get("error")?.asString() ?: body(r)) + errors
            }
            fieldErrors(post("$base/open", openBody(key(), id, channel = "SALES_AGENCY"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "intakeChannel:NOT_ALLOWED")
            // FLOW-3 Coordinator 결정(가역): `system:demo-seed` 계약 담당자는 Demo seed 경로만 쓴다 — 관리 채널은 admin: 접두만 받는다(다른 system:* 도 같음).
            listOf("system:demo-seed", "system:migration").forEach { manager ->
                fieldErrors(post("$base/open", openBody(key(), id, manager = manager))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "contractManager:FORMAT")
            }
            fieldErrors(post("$base/open", openBody(key(), id, reason = "card 4111 1111 1111 1111"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "reason:CARD_NUMBER_PATTERN")
            fieldErrors(post("$base/complete", completeBody(key(), id, 1, 1, document = "4111-1111-1111-1111"))) shouldBe
                listOf("400", "BUSINESS_VALIDATION_FAILED", "documentRef:CARD_NUMBER_PATTERN")
            post("$base/complete", completeBody(key(), id, 1, 1, document = "4111-1111-1111-1111")).let { body(it) } shouldNotContain "4111"
            fieldErrors(post("$base/complete", completeBody(key(), id, 1, 1, start = "2026-02-30"))) shouldBe listOf("400", "BUSINESS_VALIDATION_FAILED", "startDate:FORMAT")
            post("$base/open", openBody(key(), id), signed = false).response.status shouldBe 401
        }
    }
})

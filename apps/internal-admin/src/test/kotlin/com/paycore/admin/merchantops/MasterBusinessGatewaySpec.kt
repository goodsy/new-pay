package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.merchantops.adapter.integration.HttpMasterBusinessGateway
import com.paycore.admin.merchantops.adapter.integration.MasterCallSigner
import com.paycore.admin.merchantops.adapter.integration.MasterOpsSettings
import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import com.paycore.admin.merchantops.application.provided.ContractIntakeOperation
import com.paycore.admin.merchantops.application.required.MasterContractIntakeCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.support.StubMasterOpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant

/**
 * ADMIN → MASTER 사업자 호출(BUSINESS-PROFILE-001 §7.2 Gateway·§7.3 — 실제 HTTP 대역 [StubMasterOpsServer], 서명은 대역이 앱과 독립 검증):
 * 경로·메서드·서명, **목록 검색 선택 필드는 JSON null 없이 생략**(BIZ-2 인계 ④), 번호는 POST 본문에만, 응답 모양(골든 그대로 통과 — 원문 번호 키·모르는 키는 해석 불가),
 * 명령 응답 매핑(인계 ②: 400 `BUSINESS_VALIDATION_FAILED` → Invalid(효과 없음 확정), path 문법 위반 → Unknown / 인계 ③: 503 `TEMPORARILY_UNAVAILABLE` → RetrySameKey /
 * 중복 거절 existing 필수·다른 거절 existing 금지 / 409 / timeout), DB TX 안 호출 거부, 로그·예외에 값 없음(BIZ-3-6).
 */
class MasterBusinessGatewaySpec : BehaviorSpec({

    StubMasterOpsServer.start()
    val json = JsonMapper.builder().build()
    val golden: JsonNode = json.readTree(requireNotNull(javaClass.getResourceAsStream("/contracts/business-profile-response-shapes-v2.golden.json")).readAllBytes())
    fun g(name: String): String = json.writeValueAsString(golden.get(name))

    fun settings(read: Duration = Duration.ofSeconds(2)) = MasterOpsSettings(
        "http://127.0.0.1:${StubMasterOpsServer.PORT}", StubMasterOpsServer.KEY_ID, StubMasterOpsServer.KEY_BASE64, Duration.ofSeconds(1), read, 2, 30, 10, 2, 20, 2,
        minOf(read, Duration.ofMillis(500)), 60,
    )
    fun gateway(s: MasterOpsSettings = settings()) = HttpMasterBusinessGateway(s, MasterCallSigner(s.keyId, s.signingKey())) { Instant.now() }

    val base = "/internal/v1/admin/businesses"
    var seq = System.nanoTime() % 1_000_000L
    fun businessId() = "biz_01JC" + (++seq).toString().padStart(22, '0')
    fun command(op: BusinessProfileOperation, body: Map<String, Any?> = linkedMapOf("registrationStatus" to "DRAFT", "registrationNumber" to BusinessInputFixture.CANARY_NUMBER,
        "registration" to linkedMapOf("corporateRegistrationNumber" to null, "countryCode" to "KR"))) =
        MasterBusinessCommand(op, "mo1.01JC00000000000000000000V1." + "%032x".format(++seq), "admin:adm_01JC00000000000000000000V1", "mos.x", body)
    fun reply(path: String, status: Int, body: String, delay: Long = 0) = StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(status, body, delay))

    Given("queries") {
        Then("search: signed POST to /businesses/searches; absent optional fields are omitted — never JSON null (BIZ-2 인계 ④); the golden list parses") {
            reply("$base/searches", 200, g("list"))
            val before = StubMasterOpsServer.requestsForPath("$base/searches").size
            gateway().search(BusinessSearchCriteria()).shouldBeInstanceOf<MasterCallResult.Found>()
            gateway().search(BusinessSearchCriteria(registrationStatus = "DRAFT", createdTo = "2026-11-17", limit = 5)).shouldBeInstanceOf<MasterCallResult.Found>()
            val sent = StubMasterOpsServer.requestsForPath("$base/searches").drop(before)
            sent.map { String(it.body, Charsets.UTF_8) } shouldBe listOf("{}", """{"registrationStatus":"DRAFT","createdTo":"2026-11-17","limit":5}""")
            sent.forEach { listOf(it.method, it.query, it.signatureValid) shouldBe listOf("POST", null, true) }
            sent.forEach { String(it.body, Charsets.UTF_8) shouldNotContain "null" }
        }
        Then("lookup: the number only in the POST body (never in the path); found/not-found golden parse; a plain registrationNumber key in the answer is unreadable (fail-closed)") {
            reply("$base/registration-number-lookups", 200, g("lookupFound"))
            val r = gateway().lookup("9120000001").shouldBeInstanceOf<MasterCallResult.Found>()
            @Suppress("UNCHECKED_CAST")
            ((r.document["items"] as List<Map<String, Any?>>).single()).keys shouldBe setOf("businessId", "businessName", "registrationNumber", "registrationStatus")
            val req = StubMasterOpsServer.requestsForPath("$base/registration-number-lookups").last()
            String(req.body, Charsets.UTF_8) shouldBe """{"registrationNumber":"9120000001"}"""
            req.path shouldNotContain "9120000001"
            reply("$base/registration-number-lookups", 200, g("lookupNotFound"))
            gateway().lookup("9120000001") shouldBe MasterCallResult.Found(linkedMapOf("items" to emptyList<Any>()))
            val leaky = (golden.get("lookupFound").deepCopy() as ObjectNode).also { (it.get("items").get(0) as ObjectNode).put("registrationNumberMasked", "912*******") } // M-1: 옛 v1 키가 섞이면 해석 불가
            reply("$base/registration-number-lookups", 200, json.writeValueAsString(leaky))
            gateway().lookup("9120000001") shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
        }
        Then("detail: GET without body; 404 BUSINESS_NOT_FOUND → NotFound, other 404 → Unavailable; unknown field → unreadable; over 32 KiB → unreadable; history uses /history/after/{seq}") {
            val id = businessId()
            reply("$base/$id", 200, g("detail"))
            gateway().detail(id).shouldBeInstanceOf<MasterCallResult.Found>()
            StubMasterOpsServer.requestsForPath("$base/$id").last().let { listOf(it.method, it.query, it.signatureValid, it.body.size) shouldBe listOf("GET", null, true, 0) }
            reply("$base/$id", 404, """{"error":"BUSINESS_NOT_FOUND"}""")
            gateway().detail(id) shouldBe MasterCallResult.NotFound
            reply("$base/$id", 404, """{"error":"MERCHANT_NOT_FOUND"}""")
            gateway().detail(id) shouldBe MasterCallResult.Unavailable("HTTP_404")
            reply("$base/$id", 200, g("detail").replaceFirst("\"merchantCount\":1", "\"merchantCount\":1,\"registrationNumber\":\"0000000000\""))
            gateway().detail(id) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
            reply("$base/$id", 200, " ".repeat(HttpMasterBusinessGateway.QUERY_RESPONSE_MAX_BYTES + 1))
            gateway().detail(id) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
            reply("$base/$id/history/after/41", 200, g("history"))
            gateway().history(id, 41).shouldBeInstanceOf<MasterCallResult.Found>()
            reply("$base/$id/history/after/0", 404, """{"error":"BUSINESS_NOT_FOUND"}""")
            gateway().history(id, 0) shouldBe MasterCallResult.NotFound
        }
        // 기대값 변경(FLOW-2b — golden v1 → v2, §4B.15 ADMIN 행): 골든 파일 이름과 로그 비노출 대상 근거 참조 값(옛 v1 해지 근거 DEMO-TERM-0001 → v2 계약 근거 DEMO-DOC-0001)만 바뀐다.
        Then("MERCHANT-FLOW-001 FLOW-1 contract intakes: signed GET /businesses/{id}/contract-intakes without body; the golden parses; 404 BUSINESS_NOT_FOUND → NotFound; unknown key → unreadable; no document ref in logs") {
            val id = businessId()
            val section = String(requireNotNull(javaClass.getResourceAsStream("/contracts/contract-intake-section-v2.golden.json")).readAllBytes(), Charsets.UTF_8)
            reply("$base/$id/contract-intakes", 200, section)
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                val found = gateway().contractIntakes(id).shouldBeInstanceOf<MasterCallResult.Found>()
                json.writeValueAsString(found.document) shouldBe json.writeValueAsString(json.readTree(section))
            } finally {
                root.detachAppender(appender)
            }
            val messages = appender.list.map { it.formattedMessage }
            messages.forEach { it shouldNotContain "DEMO-DOC-0001" }
            messages.any { it.contains("operation=master-ops-business-contract-intakes result=Found") } shouldBe true
            StubMasterOpsServer.requestsForPath("$base/$id/contract-intakes").last().let { listOf(it.method, it.query, it.signatureValid, it.body.size) shouldBe listOf("GET", null, true, 0) }
            reply("$base/$id/contract-intakes", 404, """{"error":"BUSINESS_NOT_FOUND"}""")
            gateway().contractIntakes(id) shouldBe MasterCallResult.NotFound
            reply("$base/$id/contract-intakes", 200, section.replaceFirst("\"intakes\"", "\"extra\":1,\"intakes\""))
            gateway().contractIntakes(id) shouldBe MasterCallResult.Unavailable("RESPONSE_UNREADABLE")
            shouldThrow<IllegalArgumentException> { gateway().contractIntakes("biz_x") }
        }
    }

    Given("commands (§6.7 mapping — BIZ-2 인계 ②③)") {
        val create = "$base/commands/create"
        Then("signed POST to /commands/{create|update|complete-registration}; common fields first, then the command body with JSON null kept for optional values") {
            reply(create, 200, g("commandSucceeded"))
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe
                MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000001", null, false, null)
            val req = StubMasterOpsServer.requestsForPath(create).last()
            req.signatureValid shouldBe true
            json.readTree(req.body).propertyNames().toList() shouldBe listOf("idempotencyKey", "actor", "correlationId", "registrationStatus", "registrationNumber", "registration")
            json.readTree(req.body).get("registration").get("corporateRegistrationNumber").isNull shouldBe true
            listOf("update", "complete-registration").zip(listOf(BusinessProfileOperation.UPDATE, BusinessProfileOperation.COMPLETE_REGISTRATION)).forEach { (path, op) ->
                reply("$base/commands/$path", 200, g("commandReplayed"))
                gateway().command(command(op, linkedMapOf("businessId" to "biz_01JC0000000000000000000001"))) shouldBe
                    MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000001", null, true, null)
            }
        }
        Then("duplicate rejection carries existing (D-BIZ-003); a duplicate without existing, another rejection with existing or a reference on a rejection → Unknown(RESPONSE_UNREADABLE)") {
            reply(create, 200, g("duplicateRejected"))
            gateway().command(command(BusinessProfileOperation.CREATE)).shouldBeInstanceOf<MasterBusinessCommandResult.Decided>().existing shouldBe
                linkedMapOf("businessId" to "biz_01JC0000000000000000000001", "businessName" to "DEMO 사업자 (운영값 아님)", "registrationNumber" to "0000000000",
                    "registrationStatus" to "REGISTERED")
            reply(create, 200, g("otherRejected"))
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Decided(false, null, "BUSINESS_VERSION_CONFLICT", true, null)
            listOf(
                """{"outcome":"REJECTED","rejection":"BUSINESS_REGISTRATION_NUMBER_DUPLICATE","replayed":false}""",
                g("otherRejected").replace("}", ",\"existing\":" + json.writeValueAsString(golden.get("duplicateRejected").get("existing")) + "}"),
                """{"outcome":"REJECTED","rejection":"BUSINESS_VERSION_CONFLICT","reference":"biz_01JC0000000000000000000001","replayed":false}""",
                """{"outcome":"SUCCEEDED","reference":"mer_01JC0000000000000000000001","replayed":false}""",
            ).forEach { body ->
                reply(create, 200, body)
                withClue(body) { gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE") }
            }
        }
        // BIZ-NORM-002 M-3(BR-BIZ-311): MASTER가 저장한 사용중지 수정 거절은 다른 업무 거절과 같은 모양 — Decided(효과 없음 확정), 결과 불명이 아니다.
        Then("M-3 update 200 REJECTED BUSINESS_INACTIVE (and its replay) → Decided rejection, not Unknown/RetrySameKey") {
            val update = "$base/commands/update"
            val body = linkedMapOf<String, Any?>("businessId" to "biz_01JC0000000000000000000001")
            reply(update, 200, """{"outcome":"REJECTED","rejection":"BUSINESS_INACTIVE","replayed":false}""")
            gateway().command(command(BusinessProfileOperation.UPDATE, body)) shouldBe MasterBusinessCommandResult.Decided(false, null, "BUSINESS_INACTIVE", false, null)
            reply(update, 200, """{"outcome":"REJECTED","rejection":"BUSINESS_INACTIVE","replayed":true}""")
            gateway().command(command(BusinessProfileOperation.UPDATE, body)) shouldBe MasterBusinessCommandResult.Decided(false, null, "BUSINESS_INACTIVE", true, null)
        }
        Then("인계 ② 400 BUSINESS_VALIDATION_FAILED → Invalid with the field errors (no effect — not Unknown), incl. categories TOO_MANY and representatives[i].type; a path outside §4.6 → Unknown; REQUEST_MALFORMED → Unknown(HTTP_400)") {
            reply(create, 400, g("validationFailed"))
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Invalid(
                listOf(BusinessFieldError("contacts", "REQUIRED"), BusinessFieldError("registration.corporateRegistrationNumber", "REQUIRED")),
            )
            reply(create, 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"categories","code":"TOO_MANY"},{"path":"representatives[0].type","code":"FORMAT"}]}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe
                MasterBusinessCommandResult.Invalid(listOf(BusinessFieldError("categories", "TOO_MANY"), BusinessFieldError("representatives[0].type", "FORMAT")))
            // SEC-BIZ-001 M-1(BR-BIZ-305): MASTER 사유 카드번호 거절 code는 §4.6 집합 안(효과 없음 확정 — 결과 불명이 아님).
            reply(create, 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"reason","code":"CARD_NUMBER_PATTERN"}]}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Invalid(listOf(BusinessFieldError("reason", "CARD_NUMBER_PATTERN")))
            reply(create, 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"representatives[0].representativeType","code":"FORMAT"}]}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
            reply(create, 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"displayName","code":"FORMAT","value":"x"}]}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
            reply(create, 400, """{"error":"REQUEST_MALFORMED"}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("HTTP_400")
        }
        Then("인계 ③ 503 TEMPORARILY_UNAVAILABLE (existing-summary failure) / REQUEST_IN_PROGRESS / OUTCOME_UNKNOWN → RetrySameKey; ADMIN_WRITE_BUSY → Busy; 409 → KeyReused; timeout → Unknown(TIMEOUT)") {
            listOf("TEMPORARILY_UNAVAILABLE", "REQUEST_IN_PROGRESS", "OUTCOME_UNKNOWN").forEach { code ->
                reply(create, 503, """{"error":"$code"}""")
                gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.RetrySameKey(code)
            }
            reply(create, 503, """{"error":"ADMIN_WRITE_BUSY"}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Busy("ADMIN_WRITE_BUSY")
            reply(create, 503, """{"error":"OTHER"}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("HTTP_503")
            reply(create, 409, """{"error":"IDEMPOTENCY_KEY_REUSED"}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.KeyReused
            reply(create, 200, g("commandSucceeded"), delay = 1_500)
            gateway(settings(read = Duration.ofMillis(300))).command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("TIMEOUT")
            reply(create, 200, " ".repeat(HttpMasterBusinessGateway.COMMAND_RESPONSE_MAX_BYTES + 1))
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
        }
        Then("a call inside a DB transaction is refused before any request") {
            val before = StubMasterOpsServer.received.size
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                shouldThrow<IllegalStateException> { gateway().command(command(BusinessProfileOperation.CREATE)) }
                shouldThrow<IllegalStateException> { gateway().lookup("9120000001") }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            StubMasterOpsServer.received.size shouldBe before
        }
    }

    Given("BIZ-3 review NIT-3·NIT-4·NIT-5 (fail-closed mapping and masked toString)") {
        Then("NIT-4 400 BUSINESS_VALIDATION_FAILED with an empty fieldErrors list is a shape violation → Unknown(RESPONSE_UNREADABLE), not Invalid") {
            reply("$base/commands/create", 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[]}""")
            gateway().command(command(BusinessProfileOperation.CREATE)) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
        }
        Then("NIT-3 a result value that violates its own invariant (not reachable through the shapes today) maps to Unknown(RESPONSE_UNREADABLE) instead of throwing") {
            HttpMasterBusinessGateway.decidedOf(mapOf("outcome" to "SUCCEEDED", "reference" to null, "rejection" to "X", "replayed" to false, "existing" to null)) shouldBe
                MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
            HttpMasterBusinessGateway.decidedOf(mapOf("outcome" to "REJECTED", "reference" to "biz_01JC0000000000000000000001", "rejection" to "X", "replayed" to false)) shouldBe
                MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
            HttpMasterBusinessGateway.invalidOf(listOf(mapOf("path" to "registrationNumber.value", "code" to "FORMAT"))) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
            HttpMasterBusinessGateway.invalidOf(emptyList()) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE")
            // 대조: 정상 값은 그대로.
            HttpMasterBusinessGateway.decidedOf(mapOf("outcome" to "SUCCEEDED", "reference" to "biz_01JC0000000000000000000001", "rejection" to null, "replayed" to true)) shouldBe
                MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000001", null, true, null)
            HttpMasterBusinessGateway.invalidOf(listOf(mapOf("path" to "contacts", "code" to "REQUIRED"))) shouldBe
                MasterBusinessCommandResult.Invalid(listOf(BusinessFieldError("contacts", "REQUIRED")))
        }
        Then("NIT-5 results carrying PII or a trade name print *** instead of values") {
            val doc = linkedMapOf<String, Any?>("contacts" to listOf(linkedMapOf("email" to BusinessInputFixture.CANARY_EMAIL, "name" to BusinessInputFixture.CANARY_REPRESENTATIVE)))
            val existing = linkedMapOf<String, Any?>("businessName" to BusinessInputFixture.CANARY_NAME)
            listOf(
                com.paycore.admin.merchantops.application.provided.BusinessQueryResult.Found(doc).toString(),
                MasterCallResult.Found(doc).toString(),
                MasterBusinessCommandResult.Decided(false, null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, existing).toString(),
                com.paycore.admin.merchantops.application.provided.BusinessProfileResult.Decided("REJECTED", null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, existing).toString(),
            ).forEach { text ->
                withClue(text) {
                    listOf(BusinessInputFixture.CANARY_EMAIL, BusinessInputFixture.CANARY_REPRESENTATIVE, BusinessInputFixture.CANARY_NAME).forEach { text shouldNotContain it }
                    text shouldContain "***"
                }
            }
        }
    }

    Given("BIZ-3-6 gateway logs and exception messages") {
        Then("log lines carry operation and result class only — no number, body value or MASTER key; argument errors carry no value") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            val cmd = command(BusinessProfileOperation.CREATE)
            try {
                reply("$base/commands/create", 200, g("duplicateRejected"))
                gateway().command(cmd)
                reply("$base/registration-number-lookups", 200, g("lookupFound"))
                gateway().lookup(BusinessInputFixture.CANARY_NUMBER)
            } finally {
                root.detachAppender(appender)
            }
            val lines = appender.list.map { it.formattedMessage }
            lines.any { "operation=master-ops-business-command-create" in it } shouldBe true
            lines.forEach { line -> listOf(BusinessInputFixture.CANARY_NUMBER, cmd.idempotencyKey, "DEMO 사업자").forEach { withClue(line) { line.contains(it) shouldBe false } } }
            shouldThrow<IllegalArgumentException> { gateway().lookup("731-90-24865") }.message!! shouldNotContain "731"
            cmd.toString() shouldNotContain BusinessInputFixture.CANARY_NUMBER
        }
    }

    Given("MERCHANT-FLOW-001 FLOW-2b contract-intake commands (§4B.7 — signed POST /businesses/contract-intakes/commands/{open|move|complete})") {
        val intakeBase = "$base/contract-intakes/commands"
        val bizId = "biz_01JC0000000000000000000001"
        fun intakeCommand(op: ContractIntakeOperation, body: Map<String, Any?> = linkedMapOf("businessId" to bizId, "reason" to null)) =
            MasterContractIntakeCommand(op, "mo1.01JC00000000000000000000V1." + "%032x".format(++seq), "admin:adm_01JC00000000000000000000V1", "mos.x", body)
        Then("path per operation, signature valid, common fields first then the command body (JSON null kept); success → Decided(reference = businessId)") {
            listOf(ContractIntakeOperation.OPEN to "open", ContractIntakeOperation.MOVE to "move", ContractIntakeOperation.COMPLETE to "complete").forEach { (op, path) ->
                reply("$intakeBase/$path", 200, """{"outcome":"SUCCEEDED","reference":"$bizId","replayed":false}""")
                withClue(path) {
                    gateway().contractIntakeCommand(intakeCommand(op)) shouldBe MasterBusinessCommandResult.Decided(true, bizId, null, false, null)
                    val req = StubMasterOpsServer.requestsForPath("$intakeBase/$path").last()
                    listOf(req.method, req.signatureValid) shouldBe listOf("POST", true)
                    json.readTree(req.body).propertyNames().toList() shouldBe listOf("idempotencyKey", "actor", "correlationId", "businessId", "reason")
                    json.readTree(req.body).get("reason").isNull shouldBe true
                }
            }
        }
        Then("stored rejections: only the §4B.7 contract-intake codes are read (replayed kept); an unknown code, existing or reference on a rejection, a non-biz reference → Unknown(RESPONSE_UNREADABLE)") {
            val open = "$intakeBase/open"
            listOf(
                "BUSINESS_NOT_FOUND", "BUSINESS_NOT_ACTIVE", "CONTRACT_INTAKE_NOT_FOUND", "CONTRACT_INTAKE_OPEN_EXISTS", "CONTRACT_INTAKE_KIND_MISMATCH",
                "CONTRACT_INTAKE_RECEIVED_ON_FUTURE", "CONTRACT_INTAKE_VERSION_CONFLICT", "CONTRACT_INTAKE_TRANSITION_NOT_ALLOWED", "CONTRACT_INTAKE_REASON_REQUIRED",
                "CONTRACT_INTAKE_DOCUMENT_REQUIRED", "BUSINESS_CONTRACT_EXISTS", "BUSINESS_CONTRACT_NOT_CHANGEABLE", "BUSINESS_CONTRACT_SCHEDULED_VERSION_EXISTS",
                "BUSINESS_CONTRACT_START_RETROACTIVE", "BUSINESS_CONTRACT_PERIOD_INVALID", "BUSINESS_CONTRACT_SCOPE_INVALID",
            ).forEach { code ->
                reply(open, 200, """{"outcome":"REJECTED","rejection":"$code","replayed":true}""")
                withClue(code) { gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.OPEN)) shouldBe MasterBusinessCommandResult.Decided(false, null, code, true, null) }
            }
            listOf(
                """{"outcome":"REJECTED","rejection":"BUSINESS_VERSION_CONFLICT","replayed":false}""",
                """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_COMPLETED_EXISTS","replayed":false}""",
                """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_OPEN_EXISTS","reference":"$bizId","replayed":false}""",
                """{"outcome":"SUCCEEDED","reference":"mer_01JC0000000000000000000001","replayed":false}""",
                """{"outcome":"SUCCEEDED","reference":"$bizId","replayed":false,"contractSeq":1}""",
            ).forEach { body ->
                reply(open, 200, body)
                withClue(body) { gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.OPEN)) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE") }
            }
        }
        Then("400 BUSINESS_VALIDATION_FAILED with contract-intake paths → Invalid (no effect); a business-profile path, an unknown path or a value key → Unknown; REQUEST_MALFORMED → Unknown(HTTP_400)") {
            val complete = "$intakeBase/complete"
            reply(complete, 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"documentRef","code":"CARD_NUMBER_PATTERN"},{"path":"services[0]","code":"FORMAT"}]}""")
            gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.COMPLETE)) shouldBe
                MasterBusinessCommandResult.Invalid(listOf(BusinessFieldError("documentRef", "CARD_NUMBER_PATTERN"), BusinessFieldError("services[0]", "FORMAT")))
            reply(complete, 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"intakeChannel","code":"NOT_ALLOWED"},{"path":"desiredServices[1]","code":"FORMAT"}]}""")
            gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.COMPLETE)) shouldBe
                MasterBusinessCommandResult.Invalid(listOf(BusinessFieldError("intakeChannel", "NOT_ALLOWED"), BusinessFieldError("desiredServices[1]", "FORMAT")))
            listOf(
                """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"displayName","code":"FORMAT"}]}""",
                """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"agencyId","code":"FORMAT"}]}""",
                """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"reason","code":"FORMAT","value":"x"}]}""",
                """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[]}""",
            ).forEach { body ->
                reply(complete, 400, body)
                withClue(body) { gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.COMPLETE)) shouldBe MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE") }
            }
            reply(complete, 400, """{"error":"REQUEST_MALFORMED"}""")
            gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.COMPLETE)) shouldBe MasterBusinessCommandResult.Unknown("HTTP_400")
        }
        Then("409 → KeyReused, 503 codes → RetrySameKey/Busy, timeout → Unknown(TIMEOUT); called inside a DB transaction → IllegalStateException; logs carry no body value") {
            val move = "$intakeBase/move"
            reply(move, 409, """{"error":"IDEMPOTENCY_KEY_REUSED"}""")
            gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.MOVE)) shouldBe MasterBusinessCommandResult.KeyReused
            listOf("TEMPORARILY_UNAVAILABLE", "REQUEST_IN_PROGRESS", "OUTCOME_UNKNOWN").forEach { code ->
                reply(move, 503, """{"error":"$code"}""")
                gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.MOVE)) shouldBe MasterBusinessCommandResult.RetrySameKey(code)
            }
            reply(move, 503, """{"error":"ADMIN_WRITE_BUSY"}""")
            gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.MOVE)) shouldBe MasterBusinessCommandResult.Busy("ADMIN_WRITE_BUSY")
            reply(move, 200, """{"outcome":"SUCCEEDED","reference":"$bizId","replayed":false}""", delay = 1_500)
            gateway(settings(read = Duration.ofMillis(300))).contractIntakeCommand(intakeCommand(ContractIntakeOperation.MOVE)) shouldBe MasterBusinessCommandResult.Unknown("TIMEOUT")
            TransactionSynchronizationManager.setActualTransactionActive(true)
            try {
                shouldThrow<IllegalStateException> { gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.MOVE)) }
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false)
            }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                reply(move, 200, """{"outcome":"REJECTED","rejection":"CONTRACT_INTAKE_REASON_REQUIRED","replayed":false}""")
                gateway().contractIntakeCommand(intakeCommand(ContractIntakeOperation.MOVE, linkedMapOf("businessId" to bizId, "reason" to "고객 요청 CANARY-REASON-7")))
            } finally {
                root.detachAppender(appender)
            }
            val messages = appender.list.map { it.formattedMessage }
            messages.forEach { it shouldNotContain "CANARY-REASON-7" }
            messages.any { it.contains("operation=master-ops-contract-intake-command-move result=REJECTED:CONTRACT_INTAKE_REASON_REQUIRED") } shouldBe true
            intakeCommand(ContractIntakeOperation.MOVE, linkedMapOf("reason" to "CANARY-REASON-7")).toString() shouldNotContain "CANARY-REASON-7"
        }
    }
})


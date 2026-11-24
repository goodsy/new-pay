package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.merchantops.adapter.integration.BusinessOpsSettings
import com.paycore.admin.merchantops.adapter.web.BusinessCommandController
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.column
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.io.File

/**
 * 화면 사업자 API HTTP(BUSINESS-PROFILE-001 §7 — BIZ-3, 실제 2단계 로그인 세션·실제 MySQL 감사 행, MASTER는 실제 HTTP 대역 [StubMasterOpsServer]):
 * BIZ-3-2 역할 행렬(TC-15 — 서버 인가, 화면 숨김 무관)·상세 감사 행·사업자 조회 창(Demo 30 — 31번째 429·상세 초과 감사), BIZ-3-1 명령 응답(중복 existing·감사 targetRef = 기존 ID),
 * 인계 ②③(MASTER 400 → 400 fieldErrors / 503 → MASTER_RETRY_SAME_KEY), BIZ-3-5 응답 유실 → 같은 submissionId 재전송(같은 MASTER 키·감사 2행씩), BIZ-3-6 canary(ADMIN 전 표 문자열 열·
 * 로그·지표 label·오류 본문), BIZ-3-7 감사 correlation = MASTER로 보낸 correlation, BIZ-3-8 범위 밖 경로 404·ADMIN migration 추가 0, CSRF·415·413(감사 없음), 설정 키.
 * 공유 DB 격리: 모든 시험은 새 계정과 SecureRandom 번호·고유 businessId를 쓴다(고정 업무 키를 공유하지 않는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class BusinessHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
    private val context: org.springframework.context.ApplicationContext,
) : BehaviorSpec({

    StubMasterOpsServer.start()
    val json = JsonMapper.builder().build()
    val golden: JsonNode = json.readTree(requireNotNull(BusinessHttpSpec::class.java.getResourceAsStream("/contracts/business-profile-response-shapes-v2.golden.json")).readAllBytes())
    fun g(name: String): String = json.writeValueAsString(golden.get(name))
    var subSeq = System.nanoTime() % 1_000_000L
    fun sub() = "%032x".format(++subSeq)
    var idSeq = System.nanoTime() % 1_000_000L
    fun businessId() = "biz_01JD" + (++idSeq).toString().padStart(22, '0')
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun audits(actorId: String) = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-'), '|', correlation_id) FROM admin_audit_event " +
                "WHERE actor_id = ? AND action LIKE 'BUSINESS\\_%' ORDER BY event_id",
            actorId,
        )
    }
    fun send(client: AdminHttpClient, builder: MockHttpServletRequestBuilder, body: String? = null, xsrf: Boolean = true) =
        client.perform(if (body == null) builder else builder.contentType(MediaType.APPLICATION_JSON).content(body), xsrf)
    val base = "/internal/v1/admin/businesses"
    fun replyMaster(path: String, status: Int, body: String, delay: Long = 0) = StubMasterOpsServer.reply(path, StubMasterOpsServer.Reply(status, body, delay))

    Given("BIZ-3-2 role matrix over HTTP (TC-15 — D-BIZ-006 ① 사용자 결정 2026-11-17)") {
        Then("VIEWER, SECURITY_ADMIN and UNMASK alone → 403 on list, detail, history and number lookup; MASTER is not called") {
            listOf(withRole(AdminRole.VIEWER), ready.bootstrapped(AdminRole.SECURITY_ADMIN), withRole(AdminRole.UNMASK)).forEach { account ->
                val client = loggedIn(account)
                val id = businessId()
                val before = StubMasterOpsServer.received.size
                withClue(account) {
                    send(client, post("/api/businesses/searches"), "{}").status shouldBe 403
                    send(client, get("/api/businesses/$id")).status shouldBe 403
                    send(client, get("/api/businesses/$id/history")).status shouldBe 403
                    send(client, post("/api/businesses/registration-number-lookups"), """{"registrationNumber":"${BusinessInputFixture.randomNumber()}"}""").let {
                        it.status shouldBe 403
                        it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
                    }
                    StubMasterOpsServer.received.size shouldBe before
                }
            }
        }
        Then("APPROVER → views 200 (detail audited BUSINESS_VIEWED before return) and create / update / registration 403 + FORBIDDEN; OPERATOR → all 200") {
            val approver = withRole(AdminRole.APPROVER)
            val client = loggedIn(approver)
            val id = businessId()
            replyMaster("$base/searches", 200, g("list"))
            replyMaster("$base/$id", 200, g("detail"))
            replyMaster("$base/$id/history/after/0", 200, g("history"))
            replyMaster("$base/registration-number-lookups", 200, g("lookupFound"))
            send(client, post("/api/businesses/searches"), "{}").let {
                it.status shouldBe 200
                it.getHeader("Cache-Control")!! shouldContain "no-store"
                json.readTree(it.contentAsString) shouldBe golden.get("list")
            }
            send(client, get("/api/businesses/$id")).let { json.readTree(it.contentAsString) shouldBe golden.get("detail") }
            send(client, get("/api/businesses/$id/history")).let { json.readTree(it.contentAsString) shouldBe golden.get("history") }
            send(client, post("/api/businesses/registration-number-lookups"), """{"registrationNumber":"0000000000"}""").let {
                json.readTree(it.contentAsString) shouldBe golden.get("lookupFound")
            }
            val before = StubMasterOpsServer.received.size
            send(client, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber())).status shouldBe 403
            send(client, put("/api/businesses/$id"), BusinessInputFixture.changeJson(sub())).status shouldBe 403
            send(client, post("/api/businesses/$id/registration"), BusinessInputFixture.changeJson(sub())).status shouldBe 403
            StubMasterOpsServer.received.size shouldBe before
            audits(approver.account.id.value).map { it.split('|').let { c -> "${c[0]}|${c[1]}|${c[3]}" } } shouldBe listOf(
                "BUSINESS_VIEWED|OK|$id", "BUSINESS_BRN_LOOKUP|OK|biz_01JC0000000000000000000001",
                "BUSINESS_CREATE|FORBIDDEN|-", "BUSINESS_UPDATE|FORBIDDEN|$id", "BUSINESS_REGISTRATION_COMPLETE|FORBIDDEN|$id",
            )

            val operator = withRole(AdminRole.OPERATOR)
            val op = loggedIn(operator)
            replyMaster("$base/commands/create", 200, StubMasterOpsServer.succeeded(id))
            replyMaster("$base/commands/update", 200, StubMasterOpsServer.succeeded(id))
            replyMaster("$base/commands/complete-registration", 200, StubMasterOpsServer.succeeded(id))
            send(op, post("/api/businesses/searches"), "{}").status shouldBe 200
            send(op, get("/api/businesses/$id")).status shouldBe 200
            send(op, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber(), status = "DRAFT",
                content = BusinessInputFixture.draftMinimal())).let {
                it.status shouldBe 200
                it.contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"$id","replayed":false}"""
            }
            send(op, put("/api/businesses/$id"), BusinessInputFixture.changeJson(sub(), version = 1, reason = "상호 정정")).status shouldBe 200
            send(op, post("/api/businesses/$id/registration"), BusinessInputFixture.changeJson(sub(), version = 2)).status shouldBe 200
            audits(operator.account.id.value).map { it.split('|').let { c -> "${c[0]}|${c[1]}" } } shouldBe listOf(
                "BUSINESS_VIEWED|OK", "BUSINESS_CREATE|REQUESTED", "BUSINESS_CREATE|OK", "BUSINESS_UPDATE|REQUESTED", "BUSINESS_UPDATE|OK",
                "BUSINESS_REGISTRATION_COMPLETE|REQUESTED", "BUSINESS_REGISTRATION_COMPLETE|OK",
            )
        }
    }

    Given("BIZ-3-2 the business read window (m-2 — Demo 30 in the test config)") {
        Then("30 mixed reads pass; the 31st detail is 429 with one BUSINESS_VIEWED RATE_LIMITED row; the 31st list/history/lookup are 429; MASTER not called after 30") {
            val account = withRole(AdminRole.OPERATOR)
            val client = loggedIn(account)
            val id = businessId()
            replyMaster("$base/searches", 200, g("list"))
            replyMaster("$base/$id", 200, g("detail"))
            replyMaster("$base/$id/history/after/0", 200, g("history"))
            replyMaster("$base/registration-number-lookups", 200, g("lookupNotFound"))
            // 리뷰 MINOR-1: 창은 벽시계 분 고정 창(ActorMinuteWindows) — 34회 호출이 분 경계를 넘지 않게, 분의 끝 근처면 다음 분 시작까지 기다린다(기대값 불변 — 저장소 관례).
            awaitFreshMinute()
            val before = StubMasterOpsServer.received.size
            repeat(10) { send(client, post("/api/businesses/searches"), "{}").status shouldBe 200 }
            repeat(10) { send(client, get("/api/businesses/$id")).status shouldBe 200 }
            repeat(5) { send(client, get("/api/businesses/$id/history")).status shouldBe 200 }
            repeat(5) { send(client, post("/api/businesses/registration-number-lookups"), """{"registrationNumber":"${BusinessInputFixture.randomNumber()}"}""").status shouldBe 200 }
            StubMasterOpsServer.received.size - before shouldBe 30
            send(client, get("/api/businesses/$id")).let { (it.status to it.contentAsString) shouldBe (429 to """{"error":"RATE_LIMITED"}""") }
            send(client, post("/api/businesses/searches"), "{}").status shouldBe 429
            send(client, get("/api/businesses/$id/history")).status shouldBe 429
            send(client, post("/api/businesses/registration-number-lookups"), """{"registrationNumber":"${BusinessInputFixture.randomNumber()}"}""").status shouldBe 429
            StubMasterOpsServer.received.size - before shouldBe 30
            audits(account.account.id.value).map { it.split('|').let { c -> "${c[0]}|${c[1]}" } }.takeLast(2) shouldBe
                listOf("BUSINESS_VIEWED|RATE_LIMITED", "BUSINESS_BRN_LOOKUP|RATE_LIMITED")
        }
        Then("the settings key has no default and is 1..10000 (startup fails otherwise); main and test yml carry Demo 30") {
            shouldThrow<IllegalArgumentException> { BusinessOpsSettings(null) }.message shouldBe "paycore.admin.merchant-ops.business-reads-per-minute is required"
            shouldThrow<IllegalArgumentException> { BusinessOpsSettings(0) }
            shouldThrow<IllegalArgumentException> { BusinessOpsSettings(10_001) }
            BusinessOpsSettings(1).businessReadsPerMinute shouldBe 1
            fun yaml(resource: String): Map<String, Any?> {
                val sources = org.springframework.boot.env.YamlPropertySourceLoader().load(resource, org.springframework.core.io.ClassPathResource(resource))
                return sources.flatMap { ps -> (ps as org.springframework.core.env.EnumerablePropertySource<*>).propertyNames.map { it to ps.getProperty(it) } }.toMap()
            }
            yaml("application.yml")["paycore.admin.merchant-ops.business-reads-per-minute"].toString() shouldBe "30"
            yaml("config/application.yml")["paycore.admin.merchant-ops.business-reads-per-minute"].toString() shouldBe "30"
        }
    }

    Given("BIZ-3-1 command responses over HTTP") {
        Then("D-BIZ-003 duplicate → 200 REJECTED with existing (상호·원문 번호(D-BIZ-007)·내부 키·상태); the audit row targets the existing businessId (C-14)") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            replyMaster("$base/commands/create", 200, g("duplicateRejected"))
            val s = sub()
            val number = BusinessInputFixture.randomNumber()
            send(client, post("/api/businesses"), BusinessInputFixture.createJson(s, number)).let {
                it.status shouldBe 200
                json.readTree(it.contentAsString) shouldBe golden.get("duplicateRejected")
            }
            audits(operator.account.id.value) shouldBe listOf(
                "BUSINESS_CREATE|REQUESTED|sub=${s.take(8)};status=REGISTERED;brn=${number.take(3)}*******|-|mos.$s",
                "BUSINESS_CREATE|REJECTED|sub=${s.take(8)};replayed=false;rejection=BUSINESS_REGISTRATION_NUMBER_DUPLICATE;status=REGISTERED;brn=${number.take(3)}*******|" +
                    "biz_01JC0000000000000000000001|mos.$s",
            )
        }
        Then("인계 ② MASTER 400 BUSINESS_VALIDATION_FAILED → 400 INVALID_INPUT + the same fieldErrors (no value); audit INVALID_INPUT, not OUTCOME_UNKNOWN") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            replyMaster("$base/commands/create", 400, g("validationFailed"))
            send(client, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber())).let {
                it.status shouldBe 400
                it.contentAsString shouldBe
                    """{"error":"INVALID_INPUT","fieldErrors":[{"path":"contacts","code":"REQUIRED"},{"path":"registration.corporateRegistrationNumber","code":"REQUIRED"}]}"""
            }
            audits(operator.account.id.value).map { it.split('|')[1] } shouldBe listOf("REQUESTED", "INVALID_INPUT")
        }
        Then("인계 ③ MASTER 503 TEMPORARILY_UNAVAILABLE (incl. the existing-summary failure) → 503 MASTER_RETRY_SAME_KEY") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            replyMaster("$base/commands/create", 503, """{"error":"TEMPORARILY_UNAVAILABLE"}""")
            send(client, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber())).let {
                (it.status to it.contentAsString) shouldBe (503 to """{"error":"MASTER_RETRY_SAME_KEY"}""")
            }
        }
        Then("ADMIN rule violation → 400 with fieldErrors and no MASTER call; strict parsing (unknown key, missing key, null for a required value, registrationNumber on update) → 400 without fieldErrors") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            val before = StubMasterOpsServer.received.size
            send(client, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber(), content = BusinessInputFixture.draftMinimal())).let {
                it.status shouldBe 400
                val errors = json.readTree(it.contentAsString).get("fieldErrors")
                (0 until errors.size()).map { i -> errors.get(i).get("path").asString() } shouldBe listOf(
                    "addresses", "categories", "contacts", "registration.openingDate", "registration.phoneNumber", "representatives",
                )
            }
            val id = businessId()
            listOf(
                post("/api/businesses") to BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber(), extra = ",\"verificationStatus\":\"VERIFIED\""),
                post("/api/businesses") to BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber(), status = "INACTIVE"),
                post("/api/businesses") to BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber()).replace("\"countryCode\":\"KR\"", "\"countryCode\":null"),
                put("/api/businesses/$id") to BusinessInputFixture.changeJson(sub(), extra = ",\"registrationNumber\":\"${BusinessInputFixture.randomNumber()}\""),
                put("/api/businesses/$id") to BusinessInputFixture.changeJson(sub()).replace(",\"reason\":null", ""),
                put("/api/businesses/$id") to BusinessInputFixture.changeJson(sub()).replace("\"version\":0", "\"version\":\"0\""),
                post("/api/businesses/$id/registration") to BusinessInputFixture.changeJson(sub()).replace("\"version\":0", "\"version\":-1"),
            ).forEach { (request, body) ->
                withClue(body.take(120)) { send(client, request, body).let { (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT"}""") } }
            }
            StubMasterOpsServer.received.size shouldBe before
        }
    }

    Given("BIZ-3-5 lost response and resend with the same submissionId") {
        Then("read timeout → 503 MASTER_OUTCOME_UNKNOWN; resend → same MASTER key, correlation and body; replayed result; audit rows (REQUESTED, OUTCOME_UNKNOWN), (REQUESTED, OK) share one correlation") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            val s = sub()
            val body = BusinessInputFixture.createJson(s, BusinessInputFixture.randomNumber())
            val create = "$base/commands/create"
            val before = StubMasterOpsServer.requestsForPath(create).size
            replyMaster(create, 200, StubMasterOpsServer.succeeded(id), delay = 2_500)
            send(client, post("/api/businesses"), body).let { (it.status to it.contentAsString) shouldBe (503 to """{"error":"MASTER_OUTCOME_UNKNOWN"}""") }
            replyMaster(create, 200, StubMasterOpsServer.succeeded(id, replayed = true))
            send(client, post("/api/businesses"), body).contentAsString shouldBe """{"outcome":"SUCCEEDED","reference":"$id","replayed":true}"""
            val sent = StubMasterOpsServer.requestsForPath(create).drop(before)
            sent.size shouldBe 2
            sent.map { json.readTree(it.body).get("idempotencyKey").asString() }.distinct() shouldBe
                listOf("mo1.${operator.account.id.value.removePrefix("adm_")}.$s")
            json.readTree(sent[0].body) shouldBe json.readTree(sent[1].body)
            audits(operator.account.id.value).map { it.split('|').let { c -> "${c[1]}|${c[4]}" } } shouldBe
                listOf("REQUESTED|mos.$s", "OUTCOME_UNKNOWN|mos.$s", "REQUESTED|mos.$s", "OK|mos.$s")
        }
        Then("BIZ-3-7 the audit correlation of a successful create is the correlationId sent to MASTER (mos.<sub> — MASTER records it on master_change_history and outbox, BIZ-2)") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            replyMaster("$base/commands/create", 200, StubMasterOpsServer.succeeded(id))
            val s = sub()
            send(client, post("/api/businesses"), BusinessInputFixture.createJson(s, BusinessInputFixture.randomNumber())).status shouldBe 200
            val sent = json.readTree(StubMasterOpsServer.requestsForPath("$base/commands/create").last().body)
            sent.get("correlationId").asString() shouldBe "mos.$s"
            audits(operator.account.id.value).map { it.substringAfterLast('|') }.distinct() shouldBe listOf(sent.get("correlationId").asString())
            audits(operator.account.id.value).last().split('|')[3] shouldBe id
        }
    }

    Given("BIZ-3-6 canary values never land in ADMIN storage, logs, metric labels or error bodies") {
        Then("create/update/lookup/detail with canary values: every ADMIN string column (information_schema) has 0 hits; audit detail shows brn=731******* only; the detail response shows the raw number (D-BIZ-007)") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            val detail = (golden.get("detail").deepCopy() as ObjectNode).also { d ->
                (d.get("business") as ObjectNode).put("businessName", BusinessInputFixture.CANARY_NAME)
                // BRN-UNMASK-001(D-BIZ-007): MASTER 상세가 원문 번호를 주면 화면 응답에는 원문, ADMIN 저장·감사·로그·지표에는 0건(아래 단언 — 강화).
                    .put("registrationNumber", BusinessInputFixture.CANARY_NUMBER)
                ((d.get("representatives") as ArrayNode).get(0) as ObjectNode).put("name", BusinessInputFixture.CANARY_REPRESENTATIVE)
                ((d.get("contacts") as ArrayNode).get(0) as ObjectNode).put("email", BusinessInputFixture.CANARY_EMAIL).put("phoneNumber", BusinessInputFixture.CANARY_PHONE)
                ((d.get("addresses") as ArrayNode).get(0) as ObjectNode).put("detailAddress", BusinessInputFixture.CANARY_DETAIL_ADDRESS)
            }
            replyMaster("$base/$id", 200, json.writeValueAsString(detail))
            replyMaster("$base/commands/create", 200, StubMasterOpsServer.succeeded(id))
            replyMaster("$base/commands/update", 503, """{"error":"OUTCOME_UNKNOWN"}""")
            replyMaster("$base/registration-number-lookups", 200, g("lookupNotFound"))
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            val bodies = mutableListOf<String>()
            try {
                bodies += send(client, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.CANARY_NUMBER, content = BusinessInputFixture.canaryRegistered()))
                    .contentAsString
                bodies += send(client, put("/api/businesses/$id"), BusinessInputFixture.changeJson(sub(), reason = BusinessInputFixture.CANARY_REASON, content = BusinessInputFixture.canaryRegistered()))
                    .contentAsString
                bodies += send(client, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.CANARY_NUMBER,
                    content = BusinessInputFixture.individualRegistered(name = " " + BusinessInputFixture.CANARY_NAME))).contentAsString
                bodies += send(client, post("/api/businesses/registration-number-lookups"), """{"registrationNumber":"${BusinessInputFixture.CANARY_NUMBER}"}""").contentAsString
                bodies += send(client, post("/api/businesses/registration-number-lookups"), """{"registrationNumber":"${BusinessInputFixture.CANARY_NUMBER}","x":1}""").contentAsString
                send(client, get("/api/businesses/$id")).let {
                    it.status shouldBe 200
                    it.contentAsString shouldContain BusinessInputFixture.CANARY_EMAIL // 상세는 조회 역할에 원문(D-BIZ-006 ②) — 저장·로그에는 없다.
                    it.contentAsString shouldContain "\"registrationNumber\":\"${BusinessInputFixture.CANARY_NUMBER}\"" // 번호도 원문(D-BIZ-007) — 저장·감사·로그에는 없다.
                }
            } finally {
                root.detachAppender(appender)
            }
            val canaries = listOf(
                BusinessInputFixture.CANARY_NUMBER, BusinessInputFixture.CANARY_REPRESENTATIVE, BusinessInputFixture.CANARY_EMAIL, BusinessInputFixture.CANARY_PHONE,
                BusinessInputFixture.CANARY_DETAIL_ADDRESS, BusinessInputFixture.CANARY_REASON, BusinessInputFixture.CANARY_NAME,
            )
            // 대조: 값이 실제로 MASTER로 갔다.
            String(StubMasterOpsServer.requestsForPath("$base/commands/create").last().body, Charsets.UTF_8) shouldContain BusinessInputFixture.CANARY_REPRESENTATIVE
            AdminDbAccounts.root(container).use { c ->
                val columns = c.column(
                    "SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns WHERE table_schema = ? " +
                        "AND data_type IN ('char', 'varchar', 'tinytext', 'text', 'mediumtext', 'longtext', 'json', 'enum', 'set') ORDER BY table_name, column_name",
                    AdminDbAccounts.ADMIN_DATABASE,
                )
                (columns.size > 20) shouldBe true
                columns.forEach { tc ->
                    val (table, column) = tc.split('.')
                    canaries.forEach { canary ->
                        withClue("$tc ~ $canary") {
                            c.column("SELECT COUNT(*) FROM `$table` WHERE CAST(`$column` AS CHAR) LIKE ?", "%$canary%") shouldBe listOf("0")
                        }
                    }
                }
            }
            audits(operator.account.id.value).filter { it.startsWith("BUSINESS_CREATE|") || it.startsWith("BUSINESS_BRN_LOOKUP|") }.forEach { row ->
                withClue(row) { (row.contains("brn=731*******") || row.contains("brn=INVALID")) shouldBe true }
            }
            // 대조(BRN-UNMASK-001): 원문 번호를 돌려준 상세 열람도 감사됐다(BUSINESS_VIEWED) — 그 행에도 원문이 없다(위 information_schema 0건이 덮는다).
            audits(operator.account.id.value).any { it.startsWith("BUSINESS_VIEWED|") } shouldBe true
            bodies.forEach { b -> canaries.forEach { canary -> withClue(b) { b shouldNotContain canary } } }
            appender.list.map { it.formattedMessage }.forEach { line -> canaries.forEach { canary -> withClue(line) { line shouldNotContain canary } } }
            registry.meters.flatMap { m -> m.id.tags.map { it.value } + m.id.name }.forEach { v -> canaries.forEach { canary -> withClue(v) { v shouldNotContain canary } } }
        }
    }

    Given("requests refused before the use case and BIZ-3-8 scope") {
        Then("no CSRF → 403; non-JSON → 415; over 16 KiB → 413; none audited and MASTER not called") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val before = StubMasterOpsServer.received.size
            send(client, post("/api/businesses"), BusinessInputFixture.createJson(sub(), BusinessInputFixture.randomNumber()), xsrf = false).status shouldBe 403
            client.perform(post("/api/businesses/registration-number-lookups").contentType(MediaType.TEXT_PLAIN).content("{}")).status shouldBe 415
            val over = "{\"pad\":\"" + "a".repeat(BusinessCommandController.MAX_BODY_BYTES) + "\"}"
            send(client, post("/api/businesses"), over).let { (it.status to it.contentAsString) shouldBe (413 to """{"error":"REQUEST_TOO_LARGE"}""") }
            send(client, post("/api/businesses/searches"), over).status shouldBe 413
            audits(operator.account.id.value).shouldBeEmpty()
            StubMasterOpsServer.received.size shouldBe before
        }
        Then("TC-22·F-011 out of scope: documents and deactivate paths do not exist (404); no ADMIN migration was added by this task") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            val id = businessId()
            listOf(get("/api/businesses/$id/documents"), get("/api/businesses/$id/deactivate")).forEach { request -> withClue(request) { send(client, request).status shouldBe 404 } }
            // 메서드와 무관하게 매핑 자체가 없다(라우트 목록 단언 — POST·PUT의 상태 코드는 정적 자원 처리기 응답에 따라 404/405).
            val routes = (context.getBean("requestMappingHandlerMapping") as org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping)
                .handlerMethods.keys.flatMap { it.patternValues }
            routes.filter { it.startsWith("/api/businesses") }.sorted() shouldBe listOf(
                "/api/businesses", "/api/businesses/registration-number-lookups", "/api/businesses/searches", "/api/businesses/{businessId}",
                "/api/businesses/{businessId}", "/api/businesses/{businessId}/history", "/api/businesses/{businessId}/registration",
            )
            routes.none { it.contains("documents") || it.contains("deactivate") } shouldBe true
            val migrations = File(System.getProperty("user.dir")).resolve("src/main/resources/db/migration/admin").list()!!.sorted()
            // BIZ-3 기준(HEAD 5dab2e7) ADMIN migration 8개 그대로(사업자 관리는 감사 action·targetRef 형식 변경이 없어 migration 불필요 — §1.4).
            migrations.size shouldBe 8
            migrations.none { it.contains("business", ignoreCase = true) } shouldBe true
        }
    }
})

/** 현재 분의 남은 시간이 [margin]보다 적으면 다음 분 시작(+200ms)까지 기다린다(분 고정 창 시험의 분 경계 회피 — 리뷰 MINOR-1). */
private fun awaitFreshMinute(margin: java.time.Duration = java.time.Duration.ofSeconds(20)) {
    val now = System.currentTimeMillis()
    val left = MINUTE_MS - now % MINUTE_MS
    if (left < margin.toMillis()) Thread.sleep(left + 200)
}

private const val MINUTE_MS = 60_000L

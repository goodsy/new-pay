package com.paycore.admin.merchantops

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.column
import com.paycore.admin.support.update
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.ZoneId

/**
 * MERCHANT-FLOW-001 FLOW-2b 화면 계약 접수 건 명령 HTTP(§4B.11 표 2b 행 — 실제 2단계 로그인 세션·실제 MySQL 감사 행, MASTER는 실제 HTTP 대역 [StubMasterOpsServer]):
 * 세 경로의 서명된 MASTER 호출과 본문, OPERATOR만 명령(APPROVER 403 + 감사), 감사 시도·결과 1행(원문 없음), ADMIN 1차 검사 400(MASTER 미호출), 계약 담당자 ACTIVE OPERATOR 검사와
 * 같은 submissionId 재전송의 재검사 없음(실제 감사 행으로 판단 — n-3), 엄격 해석(모르는 key·경로 순번) 400, 415·CSRF. 공유 DB 격리: 시험마다 새 계정·고유 businessId.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class ContractIntakeHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
) : BehaviorSpec({

    StubMasterOpsServer.start()
    val json = JsonMapper.builder().build()
    var subSeq = System.nanoTime() % 1_000_000L
    fun sub() = "%032x".format(++subSeq)
    var idSeq = System.nanoTime() % 1_000_000L
    fun businessId() = "biz_01JE" + (++idSeq).toString().padStart(22, '0')
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun audits(actorId: String) = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-'), '|', correlation_id) FROM admin_audit_event " +
                "WHERE actor_id = ? AND action LIKE 'BUSINESS\\_CONTRACT\\_INTAKE\\_%' ORDER BY event_id",
            actorId,
        )
    }
    fun send(client: AdminHttpClient, builder: MockHttpServletRequestBuilder, body: String, xsrf: Boolean = true) =
        client.perform(builder.contentType(MediaType.APPLICATION_JSON).content(body), xsrf)
    val masterBase = "/internal/v1/admin/businesses/contract-intakes/commands"
    fun replyMaster(op: String, status: Int, body: String) = StubMasterOpsServer.reply("$masterBase/$op", StubMasterOpsServer.Reply(status, body))
    fun succeeded(id: String) = """{"outcome":"SUCCEEDED","reference":"$id","replayed":false}"""
    val today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
    // 기대값 변경(MERCHANT-FLOW-001 FLOW-2d — D-GOV-024 Q-FLOW-40 사용자 결정: 접수 생성도 담당자 **불투명 참조** 방식): 본문 `contractManagerId`(계정 키) → `contractManagerRef`.
    fun openJson(s: String, managerRef: String, reason: String? = null, extra: String = "") =
        """{"submissionId":"$s","expectedKind":"NEW","receivedOn":"$today","intakeChannel":"PHONE","contractManagerRef":"$managerRef","desiredServices":["CARD_AUTH"],""" +
            """"reason":${reason?.let { "\"$it\"" } ?: "null"}$extra}"""
    /** D-GOV-024: 이 세션이 받은 담당자 목록에서 [account]의 참조(표시명 `Admin <ULID 끝 4자>` — 목록은 최신 계정부터). */
    fun managerRef(client: AdminHttpClient, account: LoginReadyAccounts.LoginReady): String {
        val res = client.perform(get("/api/contract-managers"), false)
        res.status shouldBe 200
        val items = json.readTree(res.contentAsString).get("items")
        return (0 until items.size()).map { items.get(it) }.first { it.get("displayName").asString() == "Admin ${account.account.id.value.takeLast(4)}" }.get("ref").asString()
    }
    val unknownRef = "mgr_AAAAAAAAAAAAAAAAAAAAAA"
    fun itemsJson(s: String, managerRef: String, receivedOn: String = today, reason: String? = null) =
        """{"submissionId":"$s","expectedVersion":1,"receivedOn":"$receivedOn","intakeChannel":"WEBSITE","contractManagerRef":"$managerRef","desiredServices":["CARD_AUTH","CARD_KEYIN"],""" +
            """"reason":${reason?.let { "\"$it\"" } ?: "null"}}"""
    fun moveJson(s: String, target: String, reason: String?) = """{"submissionId":"$s","expectedVersion":1,"target":"$target","reason":${reason?.let { "\"$it\"" } ?: "null"}}"""
    fun completeJson(s: String, documentRef: String = "DEMO-DOC-0001", start: String = today, end: String? = null) =
        """{"submissionId":"$s","expectedVersion":1,"documentRef":"$documentRef","startDate":"$start","endDate":${end?.let { "\"$it\"" } ?: "null"},""" +
            """"services":["CARD_PG_PAYMENT"],"reason":null}"""

    Given("FLOW-2b three commands over HTTP (signed MASTER call, audit attempt + result)") {
        Then("OPERATOR open / move / complete → 200; MASTER gets the signed body (manager admin:adm_…, path intake seq); audit rows hold kind/target and presence only") {
            val operator = withRole(AdminRole.OPERATOR)
            val manager = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            listOf("open", "move", "complete").forEach { replyMaster(it, 200, succeeded(id)) }
            val (s1, s2, s3) = Triple(sub(), sub(), sub())
            send(client, post("/api/businesses/$id/contract-intakes"), openJson(s1, managerRef(client, manager), reason = "CANARY-OPEN-1")).let {
                it.status shouldBe 200
                it.getHeader("Cache-Control")!! shouldContain "no-store"
                it.contentAsString shouldBe succeeded(id)
            }
            send(client, post("/api/businesses/$id/contract-intakes/2/transitions"), moveJson(s2, "REJECTED", "CANARY-MOVE-2")).status shouldBe 200
            send(client, post("/api/businesses/$id/contract-intakes/2/completion"), completeJson(s3, documentRef = "CANARY-DOC-3")).status shouldBe 200
            val open = StubMasterOpsServer.requestsForPath("$masterBase/open").last()
            open.signatureValid shouldBe true
            json.readTree(open.body).let { b ->
                b.propertyNames().toList() shouldBe listOf(
                    "idempotencyKey", "actor", "correlationId", "businessId", "expectedKind", "receivedOn", "intakeChannel", "contractManager", "desiredServices", "reason",
                )
                b.get("idempotencyKey").asString() shouldBe "mo1.${operator.account.id.value.removePrefix("adm_")}.$s1"
                b.get("actor").asString() shouldBe "admin:${operator.account.id.value}"
                b.get("contractManager").asString() shouldBe "admin:${manager.account.id.value}"
                b.get("businessId").asString() shouldBe id
            }
            json.readTree(StubMasterOpsServer.requestsForPath("$masterBase/move").last().body).get("intakeSeq").asInt() shouldBe 2
            json.readTree(StubMasterOpsServer.requestsForPath("$masterBase/complete").last().body).let { b ->
                listOf(b.get("documentRef").asString(), b.get("startDate").asString(), b.get("endDate").isNull) shouldBe listOf("CANARY-DOC-3", today, true)
            }
            audits(operator.account.id.value) shouldBe listOf(
                "BUSINESS_CONTRACT_INTAKE_OPEN|REQUESTED|sub=${s1.take(8)};kind=NEW;reason=PRESENT;evidence=ABSENT|$id|mos.$s1",
                "BUSINESS_CONTRACT_INTAKE_OPEN|OK|sub=${s1.take(8)};replayed=false;kind=NEW;reason=PRESENT;evidence=ABSENT|$id|mos.$s1",
                "BUSINESS_CONTRACT_INTAKE_MOVE|REQUESTED|sub=${s2.take(8)};target=REJECTED;reason=PRESENT;evidence=ABSENT|$id|mos.$s2",
                "BUSINESS_CONTRACT_INTAKE_MOVE|OK|sub=${s2.take(8)};replayed=false;target=REJECTED;reason=PRESENT;evidence=ABSENT|$id|mos.$s2",
                "BUSINESS_CONTRACT_INTAKE_COMPLETE|REQUESTED|sub=${s3.take(8)};target=COMPLETED;reason=ABSENT;evidence=PRESENT|$id|mos.$s3",
                "BUSINESS_CONTRACT_INTAKE_COMPLETE|OK|sub=${s3.take(8)};replayed=false;target=COMPLETED;reason=ABSENT;evidence=PRESENT|$id|mos.$s3",
            )
            // 원문 비노출(BIZ-3-6 canary 관례): ADMIN 감사 표 어느 문자열 열에도 사유·근거 원문이 없다.
            AdminDbAccounts.root(container).use { c ->
                c.column(
                    "SELECT CONCAT_WS('|', action, result_code, COALESCE(detail, ''), COALESCE(reason, ''), COALESCE(target_ref, '')) FROM admin_audit_event WHERE actor_id = ?",
                    operator.account.id.value,
                ).forEach { row -> listOf("CANARY", manager.account.id.value).forEach { needle -> withClue(row) { row shouldNotContain needle } } }
            }
        }
        Then("stored MASTER rejection → 200 REJECTED (same shape as business commands); MASTER 400 with contract-intake paths → 400 INVALID_INPUT + fieldErrors") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            replyMaster("complete", 200, """{"outcome":"REJECTED","rejection":"BUSINESS_CONTRACT_NOT_CHANGEABLE","replayed":false}""")
            send(client, post("/api/businesses/$id/contract-intakes/3/completion"), completeJson(sub())).let {
                (it.status to it.contentAsString) shouldBe (200 to """{"outcome":"REJECTED","rejection":"BUSINESS_CONTRACT_NOT_CHANGEABLE","replayed":false}""")
            }
            replyMaster("complete", 400, """{"error":"BUSINESS_VALIDATION_FAILED","fieldErrors":[{"path":"documentRef","code":"FORMAT"}]}""")
            send(client, post("/api/businesses/$id/contract-intakes/3/completion"), completeJson(sub())).let {
                (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"documentRef","code":"FORMAT"}]}""")
            }
            audits(operator.account.id.value).map { it.split('|')[1] } shouldBe listOf("REQUESTED", "REJECTED", "REQUESTED", "INVALID_INPUT")
        }
    }

    Given("permissions and ADMIN first checks over HTTP") {
        Then("APPROVER → 403 FORBIDDEN on the three commands with a FORBIDDEN audit row each; MASTER is not called") {
            val approver = withRole(AdminRole.APPROVER)
            val client = loggedIn(approver)
            val id = businessId()
            val before = StubMasterOpsServer.received.size
            send(client, post("/api/businesses/$id/contract-intakes"), openJson(sub(), unknownRef)).let { // APPROVER는 목록을 받지 못한다(역할 검사 403이 먼저)
                (it.status to it.contentAsString) shouldBe (403 to """{"error":"FORBIDDEN"}""")
            }
            send(client, post("/api/businesses/$id/contract-intakes/1/transitions"), moveJson(sub(), "DOCUMENT_RECEIVED", null)).status shouldBe 403
            send(client, post("/api/businesses/$id/contract-intakes/1/completion"), completeJson(sub())).status shouldBe 403
            StubMasterOpsServer.received.size shouldBe before
            audits(approver.account.id.value).map { it.split('|').let { c -> "${c[0]}|${c[1]}|${c[3]}" } } shouldBe listOf(
                "BUSINESS_CONTRACT_INTAKE_OPEN|FORBIDDEN|$id", "BUSINESS_CONTRACT_INTAKE_MOVE|FORBIDDEN|$id", "BUSINESS_CONTRACT_INTAKE_COMPLETE|FORBIDDEN|$id",
            )
        }
        Then("400 with fieldErrors and no MASTER call: card number in the reason / document ref, revert without reason, past start date, SALES_AGENCY, APPROVER as contract manager") {
            val operator = withRole(AdminRole.OPERATOR)
            val approverManager = withRole(AdminRole.APPROVER)
            val client = loggedIn(operator)
            val id = businessId()
            val before = StubMasterOpsServer.received.size
            val yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString()
            listOf(
                post("/api/businesses/$id/contract-intakes/1/transitions") to moveJson(sub(), "REJECTED", "카드 4111 1111 1111 1111") to "reason|CARD_NUMBER_PATTERN",
                post("/api/businesses/$id/contract-intakes/1/transitions") to moveJson(sub(), "CONSULTING", null) to "reason|REQUIRED",
                post("/api/businesses/$id/contract-intakes/1/completion") to completeJson(sub(), documentRef = "DOC-4111111111111111") to "documentRef|CARD_NUMBER_PATTERN",
                post("/api/businesses/$id/contract-intakes/1/completion") to completeJson(sub(), start = yesterday) to "startDate|NOT_ALLOWED",
                post("/api/businesses/$id/contract-intakes") to openJson(sub(), managerRef(client, operator)).replace("PHONE", "SALES_AGENCY") to "intakeChannel|NOT_ALLOWED",
                post("/api/businesses/$id/contract-intakes") to openJson(sub(), unknownRef) to "contractManager|NOT_ALLOWED", // 기대값 변경(D-GOV-024): APPROVER 계정은 목록에 없다 — 모르는 참조 = 같은 code
            ).forEach { (request, expected) ->
                val (builder, body) = request
                val (field, code) = expected.split('|')
                withClue(expected) {
                    send(client, builder, body).let {
                        (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"$field","code":"$code"}]}""")
                        it.contentAsString shouldNotContain "4111"
                    }
                }
            }
            StubMasterOpsServer.received.size shouldBe before
            audits(operator.account.id.value) shouldBe emptyList()
        }
        Then("strict parsing → 400 INVALID_INPUT without fieldErrors (unknown key, null kind, bad intake seq in the path, string version); 415 non-JSON; no CSRF → 403; MASTER not called") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            val before = StubMasterOpsServer.received.size
            listOf(
                post("/api/businesses/$id/contract-intakes") to openJson(sub(), managerRef(client, operator), extra = ",\"agencyId\":null"),
                post("/api/businesses/$id/contract-intakes") to openJson(sub(), managerRef(client, operator)).replace("\"NEW\"", "null"),
                post("/api/businesses/$id/contract-intakes") to openJson(sub(), managerRef(client, operator)).replace("\"desiredServices\":[\"CARD_AUTH\"]", "\"desiredServices\":[\"CARD_AUTH\",\"CARD_AUTH\"]"),
                post("/api/businesses/$id/contract-intakes/0/transitions") to moveJson(sub(), "DOCUMENT_RECEIVED", null),
                post("/api/businesses/$id/contract-intakes/01/transitions") to moveJson(sub(), "DOCUMENT_RECEIVED", null),
                post("/api/businesses/$id/contract-intakes/1/transitions") to moveJson(sub(), "COMPLETED", null),
                post("/api/businesses/$id/contract-intakes/1/completion") to completeJson(sub()).replace("\"expectedVersion\":1", "\"expectedVersion\":\"1\""),
            ).forEach { (builder, body) ->
                withClue(body.take(100)) { send(client, builder, body).let { (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT"}""") } }
            }
            client.perform(post("/api/businesses/$id/contract-intakes").contentType(MediaType.TEXT_PLAIN).content("x"), true).status shouldBe 415
            send(client, post("/api/businesses/$id/contract-intakes"), openJson(sub(), managerRef(client, operator)), xsrf = false).status shouldBe 403
            StubMasterOpsServer.received.size shouldBe before
        }
    }

    Given("n-3 resend with the same submissionId (real audit rows decide)") {
        // 기대값 변경(FLOW-2b 리뷰 MINOR-1 — Coordinator 결정, 강화): 계약 담당자 ACTIVE OPERATOR 검사는 같은 submissionId 재전송에도 항상 한다 → 400(MASTER 재호출 없음).
        Then("first send → MASTER 503 OUTCOME_UNKNOWN; the manager is suspended; the same submissionId resend is re-checked for the manager → 400 (MASTER not called again); a new submissionId → 400") {
            val operator = withRole(AdminRole.OPERATOR)
            val manager = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            val s = sub()
            replyMaster("open", 503, """{"error":"OUTCOME_UNKNOWN"}""")
            val ref = managerRef(client, manager)
            send(client, post("/api/businesses/$id/contract-intakes"), openJson(s, ref)).let {
                (it.status to it.contentAsString) shouldBe (503 to """{"error":"MASTER_RETRY_SAME_KEY"}""")
            }
            AdminDbAccounts.root(container).use {
                it.update("UPDATE admin_account SET status = 'SUSPENDED', status_before_suspend = 'ACTIVE' WHERE actor_id = ?", manager.account.id.value) shouldBe 1
            }
            val calls = StubMasterOpsServer.requestsForPath("$masterBase/open").size
            send(client, post("/api/businesses/$id/contract-intakes"), openJson(s, ref)).let {
                (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"contractManager","code":"NOT_ALLOWED"}]}""")
            }
            StubMasterOpsServer.requestsForPath("$masterBase/open").size shouldBe calls
            StubMasterOpsServer.requestsForPath("$masterBase/open").takeLast(1).map { json.readTree(it.body).get("idempotencyKey").asString() } shouldBe
                listOf("mo1.${operator.account.id.value.removePrefix("adm_")}.$s")
            send(client, post("/api/businesses/$id/contract-intakes"), openJson(sub(), ref)).let {
                (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"contractManager","code":"NOT_ALLOWED"}]}""")
            }
            audits(operator.account.id.value).map { it.split('|')[1] } shouldBe listOf("REQUESTED", "OUTCOME_UNKNOWN")
        }
    }

    Given("FLOW-2d contract-manager list (D-GOV-024 Q-FLOW-39·40 — display names only, OPERATOR only, session-bound opaque references)") {
        Then("OPERATOR → 200 no-store {items:[{ref, displayName, self}], truncated}: active OPERATORs only (self once, marked); no account key, login name, other role or suspended account") {
            val operator = withRole(AdminRole.OPERATOR)
            val manager = withRole(AdminRole.OPERATOR)
            val approverOnly = withRole(AdminRole.APPROVER)
            val suspended = withRole(AdminRole.OPERATOR)
            AdminDbAccounts.root(container).use {
                it.update("UPDATE admin_account SET status = 'SUSPENDED', status_before_suspend = 'ACTIVE' WHERE actor_id = ?", suspended.account.id.value) shouldBe 1
            }
            val client = loggedIn(operator)
            val res = client.perform(get("/api/contract-managers"), false)
            res.status shouldBe 200
            res.getHeader("Cache-Control")!! shouldContain "no-store"
            val tree = json.readTree(res.contentAsString)
            tree.propertyNames().toList() shouldBe listOf("items", "truncated")
            val items = (0 until tree.get("items").size()).map { tree.get("items").get(it) }
            items.forEach { it.propertyNames().toList() shouldBe listOf("ref", "displayName", "self"); it.get("ref").asString() shouldMatch Regex("^mgr_[A-Za-z0-9_-]{22}$") }
            fun nameOf(a: LoginReadyAccounts.LoginReady) = "Admin ${a.account.id.value.takeLast(4)}"
            items.filter { it.get("self").asBoolean() }.map { it.get("displayName").asString() } shouldBe listOf(nameOf(operator))
            items.any { it.get("displayName").asString() == nameOf(manager) } shouldBe true
            items.none { it.get("displayName").asString() == nameOf(approverOnly) || it.get("displayName").asString() == nameOf(suspended) } shouldBe true
            listOf("adm_", operator.loginName, manager.loginName, "OPERATOR", "APPROVER").forEach { res.contentAsString shouldNotContain it }
        }

        Then("재리뷰 MAJOR-1: with businessId + intakeSeq the stored manager (by account key, from the MASTER section) is current:true — others false; no account key; malformed / partial target → 400; unknown intake → 200 without current") {
            val operator = withRole(AdminRole.OPERATOR)
            val manager = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            val section = (json.readTree(javaClass.getResource("/contracts/contract-intake-section-v2.golden.json")!!.readText()) as tools.jackson.databind.node.ObjectNode).also { root ->
                root.put("businessId", id)
                ((root.get("intakes") as tools.jackson.databind.node.ArrayNode).get(0) as tools.jackson.databind.node.ObjectNode).put("contractManager", "admin:${manager.account.id.value}")
            }
            StubMasterOpsServer.reply("/internal/v1/admin/businesses/$id/contract-intakes", StubMasterOpsServer.Reply(200, json.writeValueAsString(section)))
            val seq = section.get("intakes").get(0).get("intakeSeq").asInt()
            val res = client.perform(get("/api/contract-managers").param("businessId", id).param("intakeSeq", "$seq"), false)
            res.status shouldBe 200
            val items = json.readTree(res.contentAsString).get("items").let { arr -> (0 until arr.size()).map { arr.get(it) } }
            items.forEach { it.propertyNames().toList() shouldBe listOf("ref", "displayName", "self", "current") }
            items.filter { it.get("current").asBoolean() }.map { it.get("displayName").asString() } shouldBe listOf("Admin ${manager.account.id.value.takeLast(4)}")
            res.contentAsString shouldNotContain "adm_"
            // 대상 없음(현행) → current 키 없음.
            json.readTree(client.perform(get("/api/contract-managers"), false).contentAsString).get("items").get(0).has("current") shouldBe false
            // 없는 접수 건 순번 → 200, current 없음(존재 여부를 구별하지 않는다).
            json.readTree(client.perform(get("/api/contract-managers").param("businessId", id).param("intakeSeq", "999"), false).contentAsString).get("items")
                .let { arr -> (0 until arr.size()).none { arr.get(it).get("current").asBoolean() } } shouldBe true
            listOf(
                get("/api/contract-managers").param("businessId", id),
                get("/api/contract-managers").param("intakeSeq", "1"),
                get("/api/contract-managers").param("businessId", "biz_x").param("intakeSeq", "1"),
                get("/api/contract-managers").param("businessId", id).param("intakeSeq", "0"),
            ).forEach { r -> client.perform(r, false).let { (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT"}""") } }
            val calls = StubMasterOpsServer.requestsForPath("/internal/v1/admin/businesses/$id/contract-intakes").size
            loggedIn(withRole(AdminRole.APPROVER)).perform(get("/api/contract-managers").param("businessId", id).param("intakeSeq", "$seq"), false).status shouldBe 403
            StubMasterOpsServer.requestsForPath("/internal/v1/admin/businesses/$id/contract-intakes").size shouldBe calls
        }

        Then("Observability/PII: no log line during the list and an item update carries a login name, a display name, a reference or the manager's account key") {
            val operator = withRole(AdminRole.OPERATOR)
            val manager = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            replyMaster("update-items", 200, succeeded(id))
            val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
            val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            root.addAppender(appender)
            val ref = try {
                managerRef(client, manager).also { r ->
                    send(client, post("/api/businesses/$id/contract-intakes/2/items"), itemsJson(sub(), r)).status shouldBe 200
                }
            } finally {
                root.detachAppender(appender)
            }
            val lines = appender.list.map { it.formattedMessage }
            (lines.isNotEmpty()) shouldBe true // 대조군: 명령 경로 로그가 실제로 잡혔다
            lines.forEach { line ->
                listOf(operator.loginName, manager.loginName, "Admin ${manager.account.id.value.takeLast(4)}", ref, manager.account.id.value).forEach { needle -> withClue(line) { line shouldNotContain needle } }
            }
        }

        Then("APPROVER → 403; unauthenticated → 401; a reference issued to another session (or replaced by a newer list) → contractManager NOT_ALLOWED, MASTER not called") {
            loggedIn(withRole(AdminRole.APPROVER)).perform(get("/api/contract-managers"), false).status shouldBe 403
            AdminHttpClient(mockMvc).perform(get("/api/contract-managers"), false).status shouldBe 401
            val operator = withRole(AdminRole.OPERATOR)
            val a = loggedIn(operator)
            val b = loggedIn(withRole(AdminRole.OPERATOR))
            val foreign = managerRef(a, operator)
            val id = businessId()
            val before = StubMasterOpsServer.received.size
            send(b, post("/api/businesses/$id/contract-intakes"), openJson(sub(), foreign)).let {
                (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"contractManager","code":"NOT_ALLOWED"}]}""")
            }
            val stale = managerRef(a, operator)
            managerRef(a, operator) // 새 목록 → 옛 참조 무효
            send(a, post("/api/businesses/$id/contract-intakes"), openJson(sub(), stale)).let {
                (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"contractManager","code":"NOT_ALLOWED"}]}""")
            }
            StubMasterOpsServer.received.size shouldBe before
        }
    }

    Given("FLOW-2d 접수 항목 수정 over HTTP (POST /api/businesses/{id}/contract-intakes/{seq}/items)") {
        Then("OPERATOR → signed MASTER update-items with every key (manager admin:adm_… resolved from the reference) → 200; audit target=ITEMS + presence only, no login name / account key of the manager") {
            val operator = withRole(AdminRole.OPERATOR)
            val manager = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            replyMaster("update-items", 200, succeeded(id))
            val s = sub()
            send(client, post("/api/businesses/$id/contract-intakes/2/items"), itemsJson(s, managerRef(client, manager), reason = "CANARY-ITEMS")).let {
                (it.status to it.contentAsString) shouldBe (200 to succeeded(id))
                it.getHeader("Cache-Control")!! shouldContain "no-store"
            }
            val sent = StubMasterOpsServer.requestsForPath("$masterBase/update-items").last()
            sent.signatureValid shouldBe true
            json.readTree(sent.body).let { b ->
                b.propertyNames().toList() shouldBe listOf(
                    "idempotencyKey", "actor", "correlationId", "businessId", "intakeSeq", "expectedVersion", "receivedOn", "intakeChannel", "contractManager", "desiredServices", "reason",
                )
                listOf(b.get("intakeSeq").asInt(), b.get("expectedVersion").asLong(), b.get("contractManager").asString()) shouldBe listOf(2, 1L, "admin:${manager.account.id.value}")
            }
            AdminDbAccounts.root(container).use {
                it.column(
                    "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-')) FROM admin_audit_event WHERE actor_id = ? AND action = 'BUSINESS_CONTRACT_INTAKE_ITEMS' ORDER BY event_id",
                    operator.account.id.value,
                )
            } shouldBe listOf(
                "BUSINESS_CONTRACT_INTAKE_ITEMS|REQUESTED|sub=${s.take(8)};target=ITEMS;reason=PRESENT;evidence=ABSENT",
                "BUSINESS_CONTRACT_INTAKE_ITEMS|OK|sub=${s.take(8)};replayed=false;target=ITEMS;reason=PRESENT;evidence=ABSENT",
            )
            AdminDbAccounts.root(container).use { c ->
                c.column(
                    "SELECT CONCAT_WS('|', action, result_code, COALESCE(detail, ''), COALESCE(reason, ''), COALESCE(target_ref, '')) FROM admin_audit_event WHERE actor_id = ?",
                    operator.account.id.value,
                ).forEach { row -> listOf("CANARY", manager.account.id.value, manager.loginName, operator.loginName, "mgr_").forEach { needle -> withClue(row) { row shouldNotContain needle } } }
            }
        }

        Then("stored MASTER rejections CONTRACT_INTAKE_ITEMS_UNCHANGED / CONTRACT_INTAKE_ITEMS_NOT_EDITABLE → 200 REJECTED; unknown reference → 400 NOT_ALLOWED; APPROVER → 403; strict body (unknown key, string version, bad seq) → 400") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            listOf("CONTRACT_INTAKE_ITEMS_UNCHANGED", "CONTRACT_INTAKE_ITEMS_NOT_EDITABLE").forEach { code ->
                replyMaster("update-items", 200, """{"outcome":"REJECTED","rejection":"$code","replayed":false}""")
                send(client, post("/api/businesses/$id/contract-intakes/2/items"), itemsJson(sub(), managerRef(client, operator))).let {
                    (it.status to it.contentAsString) shouldBe (200 to """{"outcome":"REJECTED","rejection":"$code","replayed":false}""")
                }
            }
            val before = StubMasterOpsServer.received.size
            send(client, post("/api/businesses/$id/contract-intakes/2/items"), itemsJson(sub(), unknownRef)).let {
                (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"contractManager","code":"NOT_ALLOWED"}]}""")
            }
            val ref = managerRef(client, operator)
            listOf(
                "/api/businesses/$id/contract-intakes/2/items" to itemsJson(sub(), ref).replace("\"reason\":null", "\"reason\":null,\"x\":1"),
                "/api/businesses/$id/contract-intakes/2/items" to itemsJson(sub(), ref).replace("\"expectedVersion\":1", "\"expectedVersion\":\"1\""),
                "/api/businesses/$id/contract-intakes/0/items" to itemsJson(sub(), ref),
                "/api/businesses/$id/contract-intakes/2/items" to itemsJson(sub(), ref).replace("contractManagerRef", "contractManagerId"),
            ).forEach { (path, body) -> withClue(body.take(80)) { send(client, post(path), body).let { (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT"}""") } } }
            StubMasterOpsServer.received.size shouldBe before
            send(loggedIn(withRole(AdminRole.APPROVER)), post("/api/businesses/$id/contract-intakes/2/items"), itemsJson(sub(), unknownRef)).status shouldBe 403
        }
    }

    Given("FLOW-2c business contract command HTTP (suspensions · resumptions · termination)") {
        Then("OPERATOR → signed MASTER business-contracts call with every key → 200; APPROVER → 403; reason missing → 400 (MASTER not called); CSRF required") {
            val operator = withRole(AdminRole.OPERATOR)
            val client = loggedIn(operator)
            val id = businessId()
            val contractBase = "/internal/v1/admin/businesses/business-contracts/commands"
            listOf("suspensions" to "suspend", "resumptions" to "resume", "termination" to "terminate").forEach { (pathPart, masterOp) ->
                StubMasterOpsServer.reply("$contractBase/$masterOp", StubMasterOpsServer.Reply(200, succeeded(id)))
                val before = StubMasterOpsServer.requestsForPath("$contractBase/$masterOp").size
                val s = sub()
                send(client, post("/api/businesses/$id/business-contracts/1/$pathPart"), """{"submissionId":"$s","expectedEventCount":0,"reason":"확인 필요","evidenceRef":null}""").let {
                    (it.status to it.contentAsString) shouldBe (200 to succeeded(id))
                }
                val sent = json.readTree(StubMasterOpsServer.requestsForPath("$contractBase/$masterOp").drop(before).single().body)
                sent.propertyNames().toList() shouldBe listOf("idempotencyKey", "actor", "correlationId", "businessId", "contractSeq", "expectedEventCount", "reason", "evidenceRef")
                listOf(sent.get("contractSeq").asInt(), sent.get("expectedEventCount").asInt(), sent.get("evidenceRef").isNull) shouldBe listOf(1, 0, true)
            }
            send(client, post("/api/businesses/$id/business-contracts/1/termination"), """{"submissionId":"${sub()}","expectedEventCount":0,"reason":null,"evidenceRef":null}""").let {
                (it.status to it.contentAsString) shouldBe (400 to """{"error":"INVALID_INPUT","fieldErrors":[{"path":"reason","code":"REQUIRED"}]}""")
            }
            send(client, post("/api/businesses/$id/business-contracts/x/termination"), """{"submissionId":"${sub()}","expectedEventCount":0,"reason":"r","evidenceRef":null}""").status shouldBe 400
            send(client, post("/api/businesses/$id/business-contracts/1/termination"), """{"submissionId":"${sub()}","expectedEventCount":0,"reason":"r","evidenceRef":null}""", xsrf = false)
                .status shouldBe 403
            val approver = loggedIn(withRole(AdminRole.APPROVER))
            send(approver, post("/api/businesses/$id/business-contracts/1/suspensions"), """{"submissionId":"${sub()}","expectedEventCount":0,"reason":"r","evidenceRef":null}""").status shouldBe 403
            AdminDbAccounts.root(container).use {
                it.column(
                    "SELECT CONCAT(action, '|', result_code) FROM admin_audit_event WHERE actor_id = ? AND action IN " +
                        "('BUSINESS_CONTRACT_SUSPEND', 'BUSINESS_CONTRACT_RESUME', 'BUSINESS_CONTRACT_TERMINATE') ORDER BY event_id",
                    operator.account.id.value,
                )
            } shouldBe listOf(
                "BUSINESS_CONTRACT_SUSPEND|REQUESTED", "BUSINESS_CONTRACT_SUSPEND|OK", "BUSINESS_CONTRACT_RESUME|REQUESTED", "BUSINESS_CONTRACT_RESUME|OK",
                "BUSINESS_CONTRACT_TERMINATE|REQUESTED", "BUSINESS_CONTRACT_TERMINATE|OK",
            )
        }
    }
})

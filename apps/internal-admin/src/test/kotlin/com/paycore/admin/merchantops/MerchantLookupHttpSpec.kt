package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.column
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import com.paycore.admin.merchantops.adapter.web.MerchantController
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer

/**
 * 화면 가맹점 조회 HTTP(MERCHANT-OPS-001 §4.1·§5·AC 9·10 — 실제 2단계 로그인 세션, 실제 MySQL 감사 행, MASTER는 실제 HTTP 대역):
 * 미인증 401, 역할별 검색·상세·이력, 등록번호 검색 감사 행(평문 없음 — 감사 표 전체 열에서 원문 검색), 상세 감사, 목록·이력 감사 없음, no-store, CSRF 없는 POST 거부,
 * 응답·로그에 등록번호 원문 없음.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantLookupHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    StubMasterOpsServer.start()
    var seq = System.nanoTime() % 1_000_000L * 100 + 7
    fun merchantId() = "mer_01JC" + (++seq).toString().padStart(22, '0')
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    /**
     * action | result_code | detail | target_ref | target_merchant_id(MERCHANT-CODE-001 MC-3a 리뷰 MINOR-3 — 실제 MySQL 행의 내부 키 열도 단언: §5.6.4 C-32 표
     * MERCHANT_VIEWED FOUND = 코드 + mer_, NOT_FOUND = mer_ + mer_, 사업자(등록번호 검색) 대상 = NULL).
     */
    fun audits(actorId: String) = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-'), '|', COALESCE(target_merchant_id, '-')) FROM admin_audit_event " +
                "WHERE actor_id = ? AND action LIKE 'MERCHANT_%' ORDER BY event_id",
            actorId,
        )
    }
    fun search(client: AdminHttpClient, body: String) = client.perform(post("/api/merchant-searches").contentType(MediaType.APPLICATION_JSON).content(body))

    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { e ->
            buildString {
                append(e.loggerName).append(' ').append(e.formattedMessage)
                var t = e.throwableProxy
                while (t != null) {
                    append(" | ").append(t.className).append(": ").append(t.message)
                    t = t.cause
                }
            }
        }
    }

    val brn = "7781234560"
    val mer = merchantId()
    val biz = "biz_" + mer.removePrefix("mer_")
    StubMasterOpsServer.reply(StubMasterOpsServer.SEARCH, StubMasterOpsServer.Reply(200, StubMasterOpsServer.directoryJson(mer, biz, brn)))
    StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(mer), StubMasterOpsServer.Reply(200, StubMasterOpsServer.detailJson(mer)))
    StubMasterOpsServer.reply(StubMasterOpsServer.historyPath(mer, 0), StubMasterOpsServer.Reply(200, StubMasterOpsServer.historyJson(mer)))

    Given("no session") {
        Then("every merchant endpoint is 401 and MASTER is not called") {
            val anonymous = AdminHttpClient(mockMvc).also { it.csrf() }
            val before = StubMasterOpsServer.received.size
            search(anonymous, "{}").status shouldBe 401
            anonymous.perform(get("/api/merchants/$mer")).status shouldBe 401
            anonymous.perform(get("/api/merchants/$mer/history")).status shouldBe 401
            StubMasterOpsServer.received.size shouldBe before
        }
    }

    Given("a VIEWER") {
        val viewerAccount = withRole(AdminRole.VIEWER)
        val viewer = loggedIn(viewerAccount)
        Then("lists without audit (no-store); a registration-number search is 403 and audited with the masked number") {
            val list = search(viewer, """{"lifecycle":"ACTIVE"}""")
            list.status shouldBe 200
            list.getHeader("Cache-Control")!! shouldContain "no-store"
            list.contentAsString shouldContain mer
            audits(viewerAccount.account.id.value).shouldBeEmpty()
            val denied = search(viewer, """{"registrationNumber":"$brn"}""")
            denied.status shouldBe 403
            denied.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
            audits(viewerAccount.account.id.value) shouldBe listOf("MERCHANT_BRN_SEARCHED|FORBIDDEN|brn=778*******|-|-")
        }
        Then("detail is audited (MERCHANT_VIEWED, target merchant); history is not; the history cursor is a query parameter on the screen API only") {
            viewer.perform(get("/api/merchants/$mer")).let {
                it.status shouldBe 200
                it.getHeader("Cache-Control")!! shouldContain "no-store"
                it.contentAsString shouldContain "\"cardRoutes\""
                it.contentAsString shouldNotContain StubMasterOpsServer.INSTITUTION_CANARY
            }
            viewer.perform(get("/api/merchants/$mer/history")).status shouldBe 200
            viewer.perform(get("/api/merchants/$mer/history?after=x")).status shouldBe 400
            // 기대값 변경: MERCHANT-CODE-001 rev 0.4.9 §5.6.4 C-32 표 `MERCHANT_VIEWED`(FOUND) 행(Q-MC-4 — D-MCODE-001) — MC-3a부터 target_ref = 응답 merchantCode(현재 코드), target_merchant_id = mer_(내부 키)
            audits(viewerAccount.account.id.value).last() shouldBe "MERCHANT_VIEWED|OK|-|shopabcd12|$mer"
            audits(viewerAccount.account.id.value).count { it.startsWith("MERCHANT_VIEWED") } shouldBe 1
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.historyPath(mer, 0)).last().signatureValid shouldBe true
        }
        Then("an unknown merchant is 404 MERCHANT_NOT_FOUND (audited NOT_FOUND); a malformed id is 400 without a MASTER call") {
            val unknown = merchantId()
            viewer.perform(get("/api/merchants/$unknown")).contentAsString shouldBe """{"error":"MERCHANT_NOT_FOUND"}"""
            audits(viewerAccount.account.id.value).last() shouldBe "MERCHANT_VIEWED|NOT_FOUND|-|$unknown|$unknown"
            viewer.perform(get("/api/merchants/mer_bad")).status shouldBe 400
        }
        Then("MASTER unavailable is 503 MASTER_UNAVAILABLE (no cause in the body) and the detail is not audited (nothing was seen)") {
            val down = merchantId()
            StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(down), StubMasterOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            StubMasterOpsServer.reply(StubMasterOpsServer.historyPath(down, 0), StubMasterOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            val before = audits(viewerAccount.account.id.value)
            viewer.perform(get("/api/merchants/$down")).let {
                it.status shouldBe 503
                it.contentAsString shouldBe """{"error":"MASTER_UNAVAILABLE"}"""
                it.getHeader("Cache-Control")!! shouldContain "no-store"
            }
            viewer.perform(get("/api/merchants/$down/history")).status shouldBe 503
            audits(viewerAccount.account.id.value) shouldBe before
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.merchantPath(down)).single().signatureValid shouldBe true
        }
        Then("a malformed registration-number attempt by a VIEWER is 403 and audited FORBIDDEN with brn=INVALID (role before format, Q-MOPS-07); a malformed plain list is 400 without audit") {
            val before = audits(viewerAccount.account.id.value)
            search(viewer, """{"registrationNumber":$brn}""").let {
                it.status shouldBe 403
                it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
            }
            search(viewer, """{"limit":"5"}""").status shouldBe 400
            audits(viewerAccount.account.id.value) shouldBe before + "MERCHANT_BRN_SEARCHED|FORBIDDEN|brn=INVALID|-|-"
        }
    }

    Given("an OPERATOR and an APPROVER") {
        Then("both may search by the full number; the response carries the raw number (D-BIZ-007), the logs and every audit row never do") {
            listOf(withRole(AdminRole.OPERATOR), ready.bootstrapped(AdminRole.APPROVER)).forEach { account ->
                val client = loggedIn(account)
                lateinit var body: String
                val before = StubMasterOpsServer.received.size
                val logs = captureLogs {
                    val res = search(client, """{"registrationNumber":"$brn"}""")
                    res.status shouldBe 200
                    body = res.contentAsString
                }
                // 기대값 변경(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①): MASTER 조회 응답 번호는 원문 10자리. 감사·로그는 마스킹 그대로(아래 단언 — 약화 없음).
                body shouldContain "\"registrationNumber\":\"$brn\""
                body shouldNotContain "778*******"
                logs.filter { brn in it }.shouldBeEmpty()
                // 서명 키·이번 호출의 서명 헤더·v1 값도 로그에 없다(대조군: 게이트웨이 로그 줄이 실제로 잡혔다).
                val signatures = StubMasterOpsServer.received.drop(before).mapNotNull { it.signature }
                signatures.size shouldBe 1
                logs.count { "operation=master-ops-search" in it } shouldBe 1
                (listOf(StubMasterOpsServer.KEY_BASE64) + signatures + signatures.map { it.substringAfter("v1=") }).forEach { s -> logs.filter { s in it }.shouldBeEmpty() }
                search(client, """{"registrationNumber":"77812345"}""").let {
                    it.status shouldBe 400
                    it.contentAsString shouldBe """{"error":"INVALID_INPUT"}"""
                }
                audits(account.account.id.value) shouldBe listOf("MERCHANT_BRN_SEARCHED|OK|brn=778*******;results=1|$biz|-", "MERCHANT_BRN_SEARCHED|INVALID_INPUT|brn=INVALID|-|-")
            }
            AdminDbAccounts.root(container).use {
                it.column("SELECT COUNT(*) FROM admin_audit_event WHERE CONCAT_WS('|', action, result_code, detail, target_ref, reason, correlation_id) LIKE ?", "%$brn%")
            } shouldBe listOf("0")
            StubMasterOpsServer.received.filter { it.path == StubMasterOpsServer.SEARCH }.forEach { it.query shouldBe null }
        }
        Then("a SECURITY_ADMIN may list but not search by number (403, audited)") {
            val admin = ready.bootstrapped(AdminRole.SECURITY_ADMIN)
            val client = loggedIn(admin)
            search(client, "{}").status shouldBe 200
            search(client, """{"registrationNumber":"$brn"}""").status shouldBe 403
            audits(admin.account.id.value) shouldBe listOf("MERCHANT_BRN_SEARCHED|FORBIDDEN|brn=778*******|-|-")
        }
        Then("strict search body (Q-MOPS-07): malformed registration-number attempts are 400 INVALID_INPUT audited as brn=INVALID; other malformed bodies are 400 without audit; MASTER is not called") {
            val account = withRole(AdminRole.OPERATOR)
            val client = loggedIn(account)
            val attempts = listOf(
                """{"registrationNumber":$brn}""",
                """{"registrationNumber":["$brn"]}""",
                """{"registrationNumber":{"v":"$brn"}}""",
                """{"registrationNumber":null}""",
                """{"registrationNumber":"$brn","registrationNumber":"$brn"}""",
                """{"registrationNumber":"$brn","unknown":"x"}""",
                """{"limit":"5","registrationNumber":"$brn"}""",
                // JSON escape로 쓴 key(본문 바이트에 `N` 그대로 — Kotlin 일반 문자열의 `\\`): 해석된 이름으로 비교해 시도다.
                "{\"registration\\u004eumber\":\"$brn\",\"limit\":1,\"limit\":2}",
                "{\"registration\\u004eumber\":$brn}",
                "{\"registrationNumber\":\"$brn",
                """{"registrationNumber":"$brn"}{}""",
            )
            val others = listOf(
                """{"limit":"5"}""", """{"limit":null}""", """{"unknown":"x"}""", """{"lifecycle":"ACTIVE","lifecycle":"ACTIVE"}""", """[]""", "not json", """{"limit":1}{}""",
                """{"nested":{"registrationNumber":"$brn"}}""", """["registrationNumber"]""", """{"RegistrationNumber":"$brn"}""", "",
                // 깊은 중첩(파서 중첩 한도 초과 — 16 KiB 안): 최상위 key가 registrationNumber가 아니므로 시도 아님.
                """{"a":""" + """{"a":""".repeat(1_500) + "1" + "}".repeat(1_501),
            )
            attempts.filter { "\\u004e" in it }.size shouldBe 2
            val searchesBefore = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size
            val responses = mutableListOf<String>()
            val logs = captureLogs {
                (attempts + others).forEach { body ->
                    val res = search(client, body)
                    withClue(body) {
                        res.status shouldBe 400
                        res.contentAsString shouldBe """{"error":"INVALID_INPUT"}"""
                    }
                    responses += res.contentAsString
                }
            }
            audits(account.account.id.value) shouldBe List(attempts.size) { "MERCHANT_BRN_SEARCHED|INVALID_INPUT|brn=INVALID|-|-" }
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size shouldBe searchesBefore
            responses.filter { brn in it }.shouldBeEmpty()
            logs.filter { brn in it || "\"nested\"" in it }.shouldBeEmpty()
        }
        Then("oversized search body (Q-MOPS-08): over 16 KiB is 413 REQUEST_TOO_LARGE before parsing, even when it names registrationNumber; no audit, no MASTER call, counted; exactly 16 KiB is parsed") {
            val account = withRole(AdminRole.OPERATOR)
            val client = loggedIn(account)
            fun rejected() = registry.find(MerchantController.SEARCH_BODY_REJECTED_METRIC).tags("reason", "TOO_LARGE").counter()?.count() ?: 0.0
            val prefix = """{"registrationNumber":"$brn","pad":""""
            val over = prefix + "a".repeat(MerchantController.MAX_SEARCH_BODY_BYTES + 1 - prefix.length - 2) + "\"}"
            over.toByteArray(Charsets.UTF_8).size shouldBe MerchantController.MAX_SEARCH_BODY_BYTES + 1
            val searchesBefore = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size
            val before = rejected()
            search(client, over).let {
                it.status shouldBe 413
                it.contentAsString shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
                it.getHeader("Cache-Control")!! shouldContain "no-store"
            }
            rejected() shouldBe before + 1.0
            audits(account.account.id.value).shouldBeEmpty()
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size shouldBe searchesBefore
            val exact = """{"lifecycle":"ACTIVE"}""".padEnd(MerchantController.MAX_SEARCH_BODY_BYTES, ' ')
            search(client, exact).status shouldBe 200
        }
        Then("a non-JSON content type is 415 UNSUPPORTED_MEDIA_TYPE before reading the body; no audit, no MASTER call, counted") {
            val account = withRole(AdminRole.OPERATOR)
            val client = loggedIn(account)
            fun rejected() = registry.find(MerchantController.SEARCH_BODY_REJECTED_METRIC).tags("reason", "UNSUPPORTED_MEDIA_TYPE").counter()?.count() ?: 0.0
            val searchesBefore = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size
            val before = rejected()
            listOf(
                post("/api/merchant-searches").contentType(MediaType.TEXT_PLAIN).content("""{"registrationNumber":"$brn"}"""),
                post("/api/merchant-searches").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("registrationNumber=$brn"),
                post("/api/merchant-searches").content("""{"registrationNumber":"$brn"}"""),
            ).forEach { request ->
                client.perform(request).let {
                    it.status shouldBe 415
                    it.contentAsString shouldBe """{"error":"UNSUPPORTED_MEDIA_TYPE"}"""
                    it.getHeader("Cache-Control")!! shouldContain "no-store"
                    it.contentAsString shouldNotContain brn
                }
            }
            rejected() shouldBe before + 3.0
            audits(account.account.id.value).shouldBeEmpty()
            StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size shouldBe searchesBefore
        }
        Then("a POST without the CSRF token is refused before the use case") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            client.perform(post("/api/merchant-searches").contentType(MediaType.APPLICATION_JSON).content("{}"), xsrf = false).status shouldBe 403
        }
    }

    Given("MERCHANT-NAME-SEARCH-001 MNS-2 a name search through the screen API") {
        Then("MNS-2 a name search is 200 without audit and MASTER gets the name verbatim; format violations, a non-string name and name+code are 400 INVALID_INPUT before MASTER; logs never carry the name") {
            val account = withRole(AdminRole.VIEWER)
            val client = loggedIn(account)
            val name = "김캐너리상회"
            StubMasterOpsServer.reply(StubMasterOpsServer.SEARCH, StubMasterOpsServer.Reply(200, StubMasterOpsServer.directoryJson(mer, biz, brn)))
            val before = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).size
            val responses = mutableListOf<String>()
            val logs = captureLogs {
                search(client, """{"displayNamePrefix":"$name","lifecycle":"ACTIVE"}""").let {
                    it.status shouldBe 200
                    it.getHeader("Cache-Control")!! shouldContain "no-store"
                    it.contentAsString shouldContain mer
                }
                listOf(
                    """{"displayNamePrefix":" $name"}""", """{"displayNamePrefix":"$name "}""", """{"displayNamePrefix":""}""", """{"displayNamePrefix":"${"가".repeat(101)}"}""",
                    """{"displayNamePrefix":"$name\n"}""", """{"displayNamePrefix":12345}""", """{"displayNamePrefix":null}""",
                    """{"displayNamePrefix":"$name","merchantCode":"shopabcd12"}""",
                ).forEach { body ->
                    val res = search(client, body)
                    withClue(body) {
                        res.status shouldBe 400
                        res.contentAsString shouldBe """{"error":"INVALID_INPUT"}"""
                    }
                    responses += res.contentAsString
                }
            }
            val sent = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.SEARCH).drop(before)
            sent.size shouldBe 1
            String(sent.single().body, Charsets.UTF_8) shouldBe """{"lifecycle":"ACTIVE","displayNamePrefix":"$name"}"""
            sent.single().signatureValid shouldBe true
            audits(account.account.id.value).shouldBeEmpty()
            responses.filter { name in it }.shouldBeEmpty()
            logs.count { "operation=master-ops-search" in it } shouldBe 1
            logs.filter { name in it }.shouldBeEmpty()
        }
    }
})

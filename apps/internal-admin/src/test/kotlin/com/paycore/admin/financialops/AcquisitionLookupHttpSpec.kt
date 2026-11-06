package com.paycore.admin.financialops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.financialops.adapter.web.AcquisitionController
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubFinancialOpsServer
import com.paycore.admin.support.column
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer

/**
 * 화면 매입 현황 조회 HTTP(ACQ-VIEW-001 §4.6 — 실제 2단계 로그인 세션, 실제 MySQL 감사 행, FINANCIAL은 실제 HTTP 대역):
 * 미인증 401, D-ACQV-001 역할(VIEW_MASKED 4역할 허용·UNMASK 단독·역할 없음 403 — FINANCIAL 미호출), 목록 감사 없음, 파일 상세·레코드 쪽 `ACQUISITION_FILE_VIEWED` 감사 행,
 * NOT_FOUND 감사, FINANCIAL 불가 503(감사 없음), FINANCIAL이 허용되지 않은 값을 보내면 503(화면에 흘리지 않음 — D-ACQV-002), no-store, 413·415(감사 없음·지표), CSRF 없는 POST 거부,
 * 로그에 fileId·서명 없음.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class AcquisitionLookupHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    StubFinancialOpsServer.start()
    var seq = System.nanoTime() % 1_000_000L
    fun fileId() = "ACQ.SFTPV${++seq % 1000}.TEST.20261020.001"
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun audits(actorId: String) = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-')) FROM admin_audit_event " +
                "WHERE actor_id = ? AND action LIKE 'ACQUISITION_%' ORDER BY event_id",
            actorId,
        )
    }
    fun searchBody(client: AdminHttpClient, body: String) =
        client.perform(post("/api/acquisition-file-searches").contentType(MediaType.APPLICATION_JSON).content(body))
    fun records(client: AdminHttpClient, id: String, body: String = "{}") =
        client.perform(post("/api/acquisition-files/$id/record-searches").contentType(MediaType.APPLICATION_JSON).content(body))

    val file = fileId()
    val period = """{"businessDateFrom":"2026-10-20","businessDateTo":"2026-10-20"}"""
    StubFinancialOpsServer.reply(StubFinancialOpsServer.SEARCH, StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.pageJson(StubFinancialOpsServer.itemJson(file))))
    StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(file), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.detailJson(file)))
    StubFinancialOpsServer.reply(StubFinancialOpsServer.recordsPath(file), StubFinancialOpsServer.Reply(200, StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1))))

    Given("no session") {
        Then("every acquisition endpoint is 401 and FINANCIAL is not called") {
            val anonymous = AdminHttpClient(mockMvc).also { it.csrf() }
            val before = StubFinancialOpsServer.received.size
            searchBody(anonymous, period).status shouldBe 401
            anonymous.perform(get("/api/acquisition-files/$file")).status shouldBe 401
            records(anonymous, file).status shouldBe 401
            StubFinancialOpsServer.received.size shouldBe before
        }
    }

    Given("a VIEWER") {
        val viewerAccount = withRole(AdminRole.VIEWER)
        val viewer = loggedIn(viewerAccount)
        val actor = viewerAccount.account.id.value
        Then("searches without audit (no-store, signed FINANCIAL call with the same body keys)") {
            val list = searchBody(viewer, period)
            list.status shouldBe 200
            list.getHeader("Cache-Control")!! shouldContain "no-store"
            list.contentAsString shouldContain file
            audits(actor).shouldBeEmpty()
            StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.SEARCH).last().let {
                it.signatureValid shouldBe true
                String(it.body) shouldBe period
            }
        }
        Then("the file detail and a record page are audited (ACQUISITION_FILE_VIEWED, target file) before they are returned; the record carries responseRef") {
            viewer.perform(get("/api/acquisition-files/$file")).let {
                it.status shouldBe 200
                it.getHeader("Cache-Control")!! shouldContain "no-store"
                it.contentAsString shouldContain "\"contentSha256\""
            }
            records(viewer, file, """{"status":"ACCEPTED"}""").let {
                it.status shouldBe 200
                it.contentAsString shouldContain """"responseRef":{"kind":"RESULT","sequenceNo":1}"""
            }
            audits(actor) shouldBe listOf("ACQUISITION_FILE_VIEWED|OK|-|$file", "ACQUISITION_FILE_VIEWED|OK|-|$file")
        }
        Then("an unknown file is 404 ACQUISITION_FILE_NOT_FOUND (audited NOT_FOUND); a malformed id or body is 400 without a FINANCIAL call or audit") {
            val unknown = fileId()
            val before = audits(actor)
            viewer.perform(get("/api/acquisition-files/$unknown")).let {
                it.status shouldBe 404
                it.contentAsString shouldBe """{"error":"ACQUISITION_FILE_NOT_FOUND"}"""
            }
            audits(actor) shouldBe before + "ACQUISITION_FILE_VIEWED|NOT_FOUND|-|$unknown"
            val calls = StubFinancialOpsServer.received.size
            viewer.perform(get("/api/acquisition-files/not-a-file")).status shouldBe 400
            searchBody(viewer, """{"businessDateFrom":"2026-10-20"}""").status shouldBe 400
            searchBody(viewer, """{"businessDateFrom":"2026-10-20","businessDateTo":"2026-10-20","limit":"5"}""").let {
                it.status shouldBe 400
                it.contentAsString shouldBe """{"error":"INVALID_INPUT"}"""
            }
            records(viewer, file, """{"afterSeq":-1}""").status shouldBe 400
            StubFinancialOpsServer.received.size shouldBe calls
            audits(actor) shouldBe before + "ACQUISITION_FILE_VIEWED|NOT_FOUND|-|$unknown"
        }
        Then("FINANCIAL unavailable is 503 FINANCIAL_UNAVAILABLE (no cause in the body) and nothing is audited") {
            val down = fileId()
            StubFinancialOpsServer.reply(StubFinancialOpsServer.filePath(down), StubFinancialOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            val before = audits(actor)
            viewer.perform(get("/api/acquisition-files/$down")).let {
                it.status shouldBe 503
                it.contentAsString shouldBe """{"error":"FINANCIAL_UNAVAILABLE"}"""
            }
            audits(actor) shouldBe before
        }
        Then("a FINANCIAL response carrying a D-ACQV-002 value is not passed to the screen (503, not audited)") {
            val leaky = fileId()
            StubFinancialOpsServer.reply(
                StubFinancialOpsServer.filePath(leaky),
                StubFinancialOpsServer.Reply(
                    200,
                    StubFinancialOpsServer.detailJson(
                        leaky,
                        StubFinancialOpsServer.recordsJson(StubFinancialOpsServer.recordJson(1, recordExtra = ""","approvalNumber":"30000001","issuerCode":"SIMISSA"""")),
                    ),
                ),
            )
            val before = audits(actor)
            viewer.perform(get("/api/acquisition-files/$leaky")).let {
                it.status shouldBe 503
                it.contentAsString shouldNotContain "30000001"
            }
            audits(actor) shouldBe before
        }
    }

    Given("the other roles") {
        Then("OPERATOR, APPROVER and SECURITY_ADMIN may search and view; UNMASK-only and no role are 403 without a FINANCIAL call or audit") {
            listOf(AdminRole.OPERATOR, AdminRole.APPROVER, AdminRole.SECURITY_ADMIN).forEach { role ->
                withClue(role) {
                    val client = loggedIn(withRole(role))
                    searchBody(client, period).status shouldBe 200
                    client.perform(get("/api/acquisition-files/$file")).status shouldBe 200
                }
            }
            listOf(withRole(AdminRole.UNMASK), ready.withoutRoles()).forEach { account ->
                val client = loggedIn(account)
                val calls = StubFinancialOpsServer.received.size
                searchBody(client, period).let {
                    it.status shouldBe 403
                    it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
                }
                client.perform(get("/api/acquisition-files/$file")).status shouldBe 403
                records(client, file).status shouldBe 403
                StubFinancialOpsServer.received.size shouldBe calls
                audits(account.account.id.value).shouldBeEmpty()
            }
        }
    }

    Given("a malformed body from an account without the view role (review MINOR-2 — role before input, as the transaction search)") {
        fun malformed(client: AdminHttpClient) = listOf(
            searchBody(client, """{"businessDateFrom":"2026-10-20","businessDateTo":"2026-10-20","limit":"5"}"""),
            searchBody(client, "not json"),
            records(client, file, """{"afterSeq":"x"}"""),
            records(client, file, """{"status":"ACCEPTED","status":"REJECTED"}"""),
        )
        Then("UNMASK-only gets 403 FORBIDDEN, not 400, for a malformed search or record-search body; no FINANCIAL call or audit") {
            val unmask = withRole(AdminRole.UNMASK)
            val client = loggedIn(unmask)
            val calls = StubFinancialOpsServer.received.size
            malformed(client).forEach {
                it.status shouldBe 403
                it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
                it.getHeader("Cache-Control")!! shouldContain "no-store"
            }
            StubFinancialOpsServer.received.size shouldBe calls
            audits(unmask.account.id.value).shouldBeEmpty()
        }
        Then("a viewer SUSPENDED after login never reaches the body parser: the session layer ends the session first (401 SESSION_REPLACED, not 400); no FINANCIAL call or audit") {
            // 세션 세대는 올리지 않는다 — 계정 현재 행의 상태만으로 끊기는지 본다. 세션 층을 지난 뒤 정지되는 경쟁 구간은 Use Case가 Forbidden으로 막는다
            // (AcquisitionLookupServiceSpec — rejectMalformedSearch, SUSPENDED).
            // 첫 요청이 세션을 끝내므로(이후 요청은 CSRF·세션 없음) 본문마다 새 계정·새 세션으로 한 번씩 보낸다.
            listOf<(AdminHttpClient) -> org.springframework.mock.web.MockHttpServletResponse>(
                { searchBody(it, """{"businessDateFrom":"2026-10-20","businessDateTo":"2026-10-20","limit":"5"}""") },
                { records(it, file, """{"afterSeq":"x"}""") },
            ).forEach { send ->
                val suspended = withRole(AdminRole.VIEWER)
                val client = loggedIn(suspended)
                AdminDbAccounts.root(container).use {
                    it.prepareStatement("UPDATE admin_account SET status = 'SUSPENDED', status_before_suspend = 'ACTIVE' WHERE actor_id = ?").use { s ->
                        s.setString(1, suspended.account.id.value)
                        s.executeUpdate() shouldBe 1
                    }
                }
                val calls = StubFinancialOpsServer.received.size
                send(client).let {
                    it.status shouldBe 401
                    it.contentAsString shouldBe """{"error":"SESSION_REPLACED"}"""
                }
                StubFinancialOpsServer.received.size shouldBe calls
                audits(suspended.account.id.value).shouldBeEmpty()
            }
        }
    }

    Given("request bodies refused before reading and the CSRF rule") {
        Then("over 16 KiB is 413 and a non-JSON content type is 415, before parsing, without a FINANCIAL call or audit, counted") {
            val account = withRole(AdminRole.VIEWER)
            val client = loggedIn(account)
            fun rejected(reason: String) = registry.find(AcquisitionController.BODY_REJECTED_METRIC).tags("reason", reason).counter()?.count() ?: 0.0
            val tooLarge = rejected("TOO_LARGE")
            val unsupported = rejected("UNSUPPORTED_MEDIA_TYPE")
            val calls = StubFinancialOpsServer.received.size
            val over = period.dropLast(1) + ",\"pad\":\"" + "a".repeat(AcquisitionController.MAX_BODY_BYTES) + "\"}"
            searchBody(client, over).let {
                it.status shouldBe 413
                it.contentAsString shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
            }
            client.perform(post("/api/acquisition-files/$file/record-searches").contentType(MediaType.TEXT_PLAIN).content("{}")).let {
                it.status shouldBe 415
                it.contentAsString shouldBe """{"error":"UNSUPPORTED_MEDIA_TYPE"}"""
            }
            rejected("TOO_LARGE") shouldBe tooLarge + 1.0
            rejected("UNSUPPORTED_MEDIA_TYPE") shouldBe unsupported + 1.0
            StubFinancialOpsServer.received.size shouldBe calls
            audits(account.account.id.value).shouldBeEmpty()
            searchBody(client, period.padEnd(AcquisitionController.MAX_BODY_BYTES, ' ')).status shouldBe 200
        }
        Then("a POST without the CSRF token is refused before the use case") {
            val client = loggedIn(withRole(AdminRole.VIEWER))
            client.perform(post("/api/acquisition-file-searches").contentType(MediaType.APPLICATION_JSON).content(period), xsrf = false).status shouldBe 403
        }
        Then("no file id, signature or key appears in any log line of these calls") {
            val client = loggedIn(withRole(AdminRole.VIEWER))
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                searchBody(client, period).status shouldBe 200
                client.perform(get("/api/acquisition-files/$file")).status shouldBe 200
            } finally {
                root.detachAppender(appender)
            }
            val signature = StubFinancialOpsServer.requestsForPath(StubFinancialOpsServer.filePath(file)).last().signature!!
            withClue("control: the appender captured the gateway log") { appender.list.any { it.formattedMessage.contains("financial-ops-") } shouldBe true }
            appender.list.forEach { e ->
                listOf(file, signature.substringAfter("v1="), StubFinancialOpsServer.KEY_BASE64).forEach { e.formattedMessage shouldNotContain it }
            }
        }
    }
})

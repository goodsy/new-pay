package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.merchantops.adapter.integration.MicrometerMerchantOpsMetrics
import com.paycore.admin.merchantops.adapter.web.MerchantTargetLabelController
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.scalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.mysql.MySQLContainer

/**
 * MERCHANT-CODE-001 MC-3-17·MC-3-19 ADMIN 화면 API `POST /api/merchant-labels`(§5.6.3 MC-3b — 실제 2단계 로그인 세션, 실제 MySQL 감사 표, MASTER는 실제 HTTP 대역):
 * 미인증 401·CSRF 없는 POST 403, VIEW 4역할 200(`{"items":[{"merchantId","name","code"}],"labels":"OK"}` — merchantId 오름차순, no-store), 역할 밖 403·MASTER 0회,
 * 형식·0·51개·중복·모르는 키 400 `INVALID_INPUT`·MASTER 0회, 415·413 읽기 전 거절, MASTER 실패(503·404 — MASTER 미배포)·모양 위반 → **HTTP 200** `"labels":"UNAVAILABLE"`·items `[]`,
 * 감사 0행(`MERCHANT_VIEWED` 포함), 로그·지표 label에 키·상호·코드 없음. 분당 창 초과 429는 시험 설정 600 대신 Use Case 시험(`MerchantTargetLabelServiceSpec`)과 아래 매핑 단언으로 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class MerchantTargetLabelHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    StubMasterOpsServer.start()
    val api = "/api/merchant-labels"
    var seq = System.nanoTime() % 1_000_000L * 100 + 11
    fun merchantId() = "mer_01JD" + (++seq).toString().padStart(22, '0')
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun ids(vararg merchantIds: String) = """{"merchantIds":[${merchantIds.joinToString(",") { "\"$it\"" }}]}"""
    fun call(client: AdminHttpClient, body: String, type: MediaType = MediaType.APPLICATION_JSON) = client.perform(post(api).contentType(type).content(body))
    fun masterCalls() = StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANT_LABELS).size
    fun auditRows(actorId: String): Long = AdminDbAccounts.root(container).use { (it.scalar("SELECT COUNT(*) FROM admin_audit_event WHERE actor_id = ?", actorId) as Number).toLong() }
    fun counter(result: String) = registry.find(MicrometerMerchantOpsMetrics.TARGET_LABELS).tags("result", result).counter()?.count() ?: 0.0

    val a = merchantId()
    val b = merchantId()
    val absent = merchantId()
    fun okReply() = StubMasterOpsServer.reply(
        StubMasterOpsServer.MERCHANT_LABELS,
        StubMasterOpsServer.Reply(200, StubMasterOpsServer.merchantLabelsJson(Triple(a, "라벨상점 가", "labelshop1"), Triple(b, "Label Shop B", "labelshop2"))),
    )

    Given("no session") {
        Then("401 and MASTER is not called") {
            val anonymous = AdminHttpClient(mockMvc).also { it.csrf() }
            val before = masterCalls()
            call(anonymous, ids(a)).status shouldBe 401
            masterCalls() shouldBe before
        }
    }

    Given("MC-3-17 the four view roles") {
        Then("VIEWER, OPERATOR, APPROVER and SECURITY_ADMIN get 200 no-store items sorted by merchantId (name + code; an absent key is missing), labels OK, no audit row, metric OK") {
            okReply()
            listOf(AdminRole.VIEWER, AdminRole.OPERATOR, AdminRole.APPROVER, AdminRole.SECURITY_ADMIN).forEach { role ->
                withClue(role) {
                    val account = withRole(role)
                    val client = loggedIn(account)
                    val auditsBefore = auditRows(account.account.id.value)
                    val okBefore = counter("OK")
                    val before = masterCalls()
                    val res = call(client, ids(absent, b, a))
                    res.status shouldBe 200
                    res.getHeader("Cache-Control")!! shouldContain "no-store"
                    val sorted = listOf(a, b).sorted()
                    val expected = sorted.joinToString(",") { k ->
                        if (k == a) """{"merchantId":"$a","name":"라벨상점 가","code":"labelshop1"}""" else """{"merchantId":"$b","name":"Label Shop B","code":"labelshop2"}"""
                    }
                    res.contentAsString shouldBe """{"items":[$expected],"labels":"OK"}"""
                    masterCalls() shouldBe before + 1
                    String(StubMasterOpsServer.requestsForPath(StubMasterOpsServer.MERCHANT_LABELS).last().body, Charsets.UTF_8) shouldBe ids(*listOf(a, b, absent).sorted().toTypedArray())
                    auditRows(account.account.id.value) shouldBe auditsBefore
                    counter("OK") shouldBe okBefore + 1.0
                }
            }
        }

        Then("UNMASK-only and no role are 403 FORBIDDEN without a MASTER call") {
            listOf(withRole(AdminRole.UNMASK), ready.withoutRoles()).forEach { account ->
                val client = loggedIn(account)
                val before = masterCalls()
                call(client, ids(a)).let {
                    it.status shouldBe 403
                    it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
                }
                masterCalls() shouldBe before
            }
        }
    }

    Given("MC-3-17 input rules") {
        Then("0, 51, duplicate, MID, code, business key, unknown field, duplicate key, trailing token and non-object bodies are 400 INVALID_INPUT without a MASTER call; 415 and 413 before reading") {
            val client = loggedIn(withRole(AdminRole.VIEWER))
            val before = masterCalls()
            listOf(
                """{"merchantIds":[]}""",
                ids(*(1..51).map { merchantId() }.toTypedArray()),
                ids(a, a),
                ids("mid_01JC0000000000000000000001"),
                ids("labelshop1"),
                ids("biz_01JC0000000000000000000001"),
                """{"merchantIds":["$a"],"limit":1}""",
                """{"merchantIds":["$a"],"merchantIds":["$b"]}""",
                """{"merchantIds":["$a"]} {}""",
                """{"merchantIds":[1]}""",
                """["$a"]""",
                "not json",
            ).forEach { body ->
                withClue(body.take(80)) {
                    call(client, body).let {
                        it.status shouldBe 400
                        it.contentAsString shouldBe """{"error":"INVALID_INPUT"}"""
                        it.contentAsString shouldNotContain a
                    }
                }
            }
            call(client, ids(a), MediaType.TEXT_PLAIN).status shouldBe 415
            call(client, """{"merchantIds":["$a"]}""".padEnd(MerchantTargetLabelController.MAX_BODY_BYTES + 1, ' ')).status shouldBe 413
            masterCalls() shouldBe before
        }

        Then("a POST without the CSRF token is refused before the use case") {
            val client = loggedIn(withRole(AdminRole.VIEWER))
            val before = masterCalls()
            client.perform(post(api).contentType(MediaType.APPLICATION_JSON).content(ids(a)), xsrf = false).status shouldBe 403
            masterCalls() shouldBe before
        }
    }

    Given("MC-3-17 MASTER failure is fail-safe for the screen") {
        Then("503, 404 (MASTER not yet deployed) and a shape violation answer HTTP 200 with labels UNAVAILABLE and no items; metric UNAVAILABLE/UNREADABLE") {
            val client = loggedIn(withRole(AdminRole.APPROVER))
            listOf(
                Triple(503, """{"error":"TEMPORARILY_UNAVAILABLE"}""", "UNAVAILABLE"),
                Triple(404, "", "UNAVAILABLE"),
                Triple(200, """{"items":[{"merchantId":"$a","merchantName":"라벨상점 가","merchantCode":"labelshop1","businessId":"biz_01JC0000000000000000000001"}]}""", "UNREADABLE"),
            ).forEach { (status, body, metric) ->
                withClue("$status $metric") {
                    StubMasterOpsServer.reply(StubMasterOpsServer.MERCHANT_LABELS, StubMasterOpsServer.Reply(status, body))
                    val before = counter(metric)
                    call(client, ids(a)).let {
                        it.status shouldBe 200
                        it.contentAsString shouldBe """{"items":[],"labels":"UNAVAILABLE"}"""
                        it.getHeader("Cache-Control")!! shouldContain "no-store"
                    }
                    counter(metric) shouldBe before + 1.0
                }
            }
            okReply()
        }
    }

    Given("MC-3-17 observability without values") {
        Then("log lines and the metric labels carry no merchant key, name or code") {
            okReply()
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            try {
                call(client, ids(a, b)).status shouldBe 200
            } finally {
                root.detachAppender(appender)
            }
            val lines = appender.list.map { "${it.loggerName} ${it.formattedMessage}" }
            lines.any { "operation=merchant-target-labels result=OK requested=2" in it } shouldBe true
            lines.forEach { line -> listOf(a, b, "라벨상점 가", "Label Shop B", "labelshop1", "labelshop2").forEach { withClue(line) { line.contains(it) shouldBe false } } }
            registry.find(MicrometerMerchantOpsMetrics.TARGET_LABELS).meters().forEach { meter ->
                meter.id.tags.map { it.key } shouldBe listOf("result")
                (meter.id.getTag("result") in MicrometerMerchantOpsMetrics.TARGET_LABEL_RESULTS) shouldBe true
            }
        }
    }
})

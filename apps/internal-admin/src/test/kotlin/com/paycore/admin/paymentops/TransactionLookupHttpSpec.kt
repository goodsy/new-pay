package com.paycore.admin.paymentops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.ApprovalPath
import com.paycore.admin.paymentops.adapter.web.TransactionController
import com.paycore.admin.support.AdminDbAccounts
import com.paycore.admin.support.AdminDbTest
import com.paycore.admin.support.AdminHttpClient
import com.paycore.admin.support.StubPaymentOpsServer
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
 * 화면 거래 조회 HTTP(TXN-OPS-001 §4.1·§5·AC 6–9 — 실제 2단계 로그인 세션, 실제 MySQL 감사 행, PAYMENT는 실제 HTTP 대역 — 같은 서명 채널): 미인증 401, 역할별 검색·상세,
 * **상세만 감사**(`PAYMENT_VIEWED`, targetRef = paymentId), 검색 미감사, PAYMENT 불가 503(감사 없음), 엄격 본문 400(PAYMENT 미호출), 413·415(해석 전·감사 없음·지표), CSRF 없는 POST 거부,
 * 응답 `no-store`, 로그에 MID·주문번호·주문명 없음. 컨텍스트는 `MerchantLookupHttpSpec`과 같은 구성(TEST-PERF-002 — 새 Spring 컨텍스트를 만들지 않는다). 감사 저장 실패의 HTTP 503은
 * 별도 컨텍스트가 필요해 Use Case 시험(`TransactionLookupServiceSpec`)이 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class TransactionLookupHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    var seq = System.nanoTime() % 1_000_000L * 100 + 3
    fun paymentId() = "pay_01JE" + (++seq).toString().padStart(22, '0')
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun audits(actorId: String) = AdminDbAccounts.root(container).use {
        it.column(
            "SELECT CONCAT(action, '|', result_code, '|', COALESCE(detail, '-'), '|', COALESCE(target_ref, '-')) FROM admin_audit_event " +
                "WHERE actor_id = ? AND action LIKE 'PAYMENT_%' ORDER BY event_id",
            actorId,
        )
    }
    fun search(client: AdminHttpClient, body: String) = client.perform(post("/api/payment-searches").contentType(MediaType.APPLICATION_JSON).content(body))
    fun searches() = StubPaymentOpsServer.requestsForPath(StubPaymentOpsServer.SEARCH_PATH).size

    fun captureLogs(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { e -> e.loggerName + " " + e.formattedMessage + (e.throwableProxy?.let { " | " + it.className + ": " + it.message } ?: "") }
    }

    val order = "order-http-" + seq
    val pay = paymentId()
    StubPaymentOpsServer.search(order, StubPaymentOpsServer.Reply(200, TransactionJson.list(TransactionJson.item(pay, orderId = order))))
    StubPaymentOpsServer.detail(pay, StubPaymentOpsServer.Reply(200, TransactionJson.detail(pay)))
    val byOrder = """{"merchantAccountId":"mid-demo-1","merchantOrderId":"$order"}"""

    Given("no session") {
        Then("both endpoints are 401 and PAYMENT is not called") {
            val anonymous = AdminHttpClient(mockMvc).also { it.csrf() }
            val before = StubPaymentOpsServer.received.size
            search(anonymous, byOrder).status shouldBe 401
            anonymous.perform(get("/api/payments/$pay")).status shouldBe 401
            StubPaymentOpsServer.received.size shouldBe before
        }
    }

    Given("a VIEWER") {
        Then("searches without audit (no-store, 10-character name only); the detail is audited (PAYMENT_VIEWED, target payment) before it is returned with the full name") {
            val account = withRole(AdminRole.VIEWER)
            val viewer = loggedIn(account)
            val list = search(viewer, byOrder)
            list.status shouldBe 200
            list.getHeader("Cache-Control")!! shouldContain "no-store"
            list.contentAsString shouldContain TransactionJson.ORDER_PREVIEW
            list.contentAsString shouldNotContain TransactionJson.ORDER_FULL
            audits(account.account.id.value).shouldBeEmpty()
            val detail = viewer.perform(get("/api/payments/$pay"))
            detail.status shouldBe 200
            detail.getHeader("Cache-Control")!! shouldContain "no-store"
            detail.contentAsString shouldContain TransactionJson.ORDER_FULL
            detail.contentAsString shouldContain "SIMPTX-000111"
            audits(account.account.id.value) shouldBe listOf("PAYMENT_VIEWED|OK|-|$pay")
            val unknown = paymentId()
            viewer.perform(get("/api/payments/$unknown")).let {
                it.status shouldBe 404
                it.contentAsString shouldBe """{"error":"PAYMENT_NOT_FOUND"}"""
            }
            audits(account.account.id.value) shouldBe listOf("PAYMENT_VIEWED|OK|-|$pay", "PAYMENT_VIEWED|NOT_FOUND|-|$unknown")
            val before = StubPaymentOpsServer.received.size
            viewer.perform(get("/api/payments/pay_x")).let {
                it.status shouldBe 400
                it.contentAsString shouldBe """{"error":"INVALID_INPUT"}"""
            }
            StubPaymentOpsServer.received.size shouldBe before
        }
        Then("PAYMENT unavailable is 503 PAYMENT_UNAVAILABLE (no cause in the body) and the detail is not audited (nothing was seen)") {
            val account = withRole(AdminRole.VIEWER)
            val viewer = loggedIn(account)
            val down = paymentId()
            StubPaymentOpsServer.detail(down, StubPaymentOpsServer.Reply(503, "{\"error\":\"ADMIN_READ_BUSY\"}"))
            viewer.perform(get("/api/payments/$down")).let {
                it.status shouldBe 503
                it.contentAsString shouldBe """{"error":"PAYMENT_UNAVAILABLE"}"""
            }
            audits(account.account.id.value).shouldBeEmpty()
        }
    }

    Given("the other view roles and an account without roles") {
        Then("OPERATOR, APPROVER and SECURITY_ADMIN may search and view; an account without a view role is 403 without a PAYMENT call or audit") {
            listOf(AdminRole.OPERATOR, AdminRole.APPROVER, AdminRole.SECURITY_ADMIN).forEach { role ->
                val account = withRole(role)
                val client = loggedIn(account)
                withClue(role.name) {
                    search(client, byOrder).status shouldBe 200
                    client.perform(get("/api/payments/$pay")).status shouldBe 200
                    audits(account.account.id.value) shouldBe listOf("PAYMENT_VIEWED|OK|-|$pay")
                }
            }
            val none = ready.withoutRoles()
            val client = loggedIn(none)
            val before = StubPaymentOpsServer.received.size
            search(client, byOrder).let {
                it.status shouldBe 403
                it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
            }
            client.perform(get("/api/payments/$pay")).status shouldBe 403
            search(client, "{\"x\":1}").status shouldBe 403
            StubPaymentOpsServer.received.size shouldBe before
            audits(none.account.id.value).shouldBeEmpty()
        }
    }

    Given("search bodies (strict)") {
        Then("malformed bodies and rule violations are 400 INVALID_INPUT without a PAYMENT call or audit; nothing of the body is echoed") {
            val account = withRole(AdminRole.OPERATOR)
            val client = loggedIn(account)
            val before = searches()
            listOf(
                "", "[]", "{}", """{"merchantOrderId":"$order"}""", """{"merchantAccountId":"mid-demo-1","merchantOrderId":"$order","merchantOrderId":"$order"}""",
                """{"merchantAccountId":"mid-demo-1","merchantOrderId":"$order","orderName":"x"}""", """{"merchantAccountId":"mid-demo-1","merchantOrderId":"$order","limit":"5"}""",
                """{"merchantAccountId":"mid-demo-1","merchantOrderId":null}""", """{"createdFrom":"2026-11-01T00:00:00.000Z","createdTo":"2026-12-03T00:00:00.000Z"}""",
                """{"paymentId":"$pay","environment":"TEST"}""", """{"merchantAccountId":"mid-demo-1","merchantOrderId":"$order"} {}""",
                """{"createdFrom":"2026-11-01T00:00:00.000Z","createdTo":"2026-11-02T00:00:00.000Z","after":{"createdAt":"2026-11-01T00:00:00.000Z"}}""",
            ).forEach { body ->
                withClue(body) {
                    search(client, body).let {
                        it.status shouldBe 400
                        it.contentAsString shouldBe """{"error":"INVALID_INPUT"}"""
                    }
                }
            }
            searches() shouldBe before
            audits(account.account.id.value).shouldBeEmpty()
        }
        Then("a period search and its next page reach PAYMENT with the cursor as a body object; responses carry the PAYMENT document") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            val marker = "2026-11-03T00:00:00.000Z"
            val next = paymentId()
            // 대역 항목은 요청에 맞는 값이어야 한다(리뷰 MINOR-1 — 기간 안, cursor보다 이전, 같은 환경·상태).
            StubPaymentOpsServer.search(marker, StubPaymentOpsServer.Reply(200, TransactionJson.list(TransactionJson.item(next, createdAt = "2026-11-02T09:00:00.000Z"), next = next)))
            val body = """{"createdFrom":"2026-11-02T00:00:00.000Z","createdTo":"$marker","environment":"TEST","status":"AUTHORIZED",""" +
                """"after":{"createdAt":"2026-11-02T10:00:00.000Z","paymentId":"$pay"},"limit":2}"""
            search(client, body).let {
                it.status shouldBe 200
                it.contentAsString shouldContain "\"nextCursor\":{\"createdAt\":\"2026-11-01T01:02:03.456Z\",\"paymentId\":\"$next\"}"
            }
            val sent = StubPaymentOpsServer.received.last { it.path == StubPaymentOpsServer.SEARCH_PATH && it.body.toString(Charsets.UTF_8).contains(marker) }
            sent.signatureValid shouldBe true
            sent.body.toString(Charsets.UTF_8) shouldBe
                """{"createdFrom":"2026-11-02T00:00:00.000Z","createdTo":"$marker","environment":"TEST","status":"AUTHORIZED",""" +
                """"after":{"createdAt":"2026-11-02T10:00:00.000Z","paymentId":"$pay"},"limit":2}"""
        }
        Then("over 16 KiB is 413 REQUEST_TOO_LARGE and a non-JSON content type 415 UNSUPPORTED_MEDIA_TYPE, both before parsing, without audit or PAYMENT call, and counted") {
            val account = withRole(AdminRole.OPERATOR)
            val client = loggedIn(account)
            fun rejected(reason: String) = registry.find(TransactionController.SEARCH_BODY_REJECTED_METRIC).tags("reason", reason).counter()?.count() ?: 0.0
            val before = searches()
            val large = rejected("TOO_LARGE")
            val media = rejected("UNSUPPORTED_MEDIA_TYPE")
            val prefix = """{"merchantAccountId":"mid-demo-1","merchantOrderId":"$order","pad":""""
            val over = prefix + "a".repeat(TransactionController.MAX_SEARCH_BODY_BYTES + 1 - prefix.length - 2) + "\"}"
            over.toByteArray(Charsets.UTF_8).size shouldBe TransactionController.MAX_SEARCH_BODY_BYTES + 1
            search(client, over).let {
                it.status shouldBe 413
                it.contentAsString shouldBe """{"error":"REQUEST_TOO_LARGE"}"""
                it.getHeader("Cache-Control")!! shouldContain "no-store"
            }
            listOf(
                post("/api/payment-searches").contentType(MediaType.TEXT_PLAIN).content(byOrder),
                post("/api/payment-searches").content(byOrder),
            ).forEach { request ->
                client.perform(request).let {
                    it.status shouldBe 415
                    it.contentAsString shouldBe """{"error":"UNSUPPORTED_MEDIA_TYPE"}"""
                }
            }
            rejected("TOO_LARGE") shouldBe large + 1.0
            rejected("UNSUPPORTED_MEDIA_TYPE") shouldBe media + 2.0
            searches() shouldBe before
            audits(account.account.id.value).shouldBeEmpty()
            search(client, byOrder.padEnd(TransactionController.MAX_SEARCH_BODY_BYTES, ' ')).status shouldBe 200
        }
        Then("a POST without the CSRF token is refused before the use case") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            val before = searches()
            client.perform(post("/api/payment-searches").contentType(MediaType.APPLICATION_JSON).content(byOrder), xsrf = false).status shouldBe 403
            searches() shouldBe before
        }
    }

    Given("logs during searches and detail views") {
        Then("no MID, merchant order id, order name, payment id or signature appears in any log line") {
            val client = loggedIn(withRole(AdminRole.APPROVER))
            val lines = captureLogs {
                search(client, byOrder).status shouldBe 200
                client.perform(get("/api/payments/$pay")).status shouldBe 200
            }
            withClue("control: the calls were logged") { lines.any { it.contains("payment-ops-read") } shouldBe true }
            val signature = StubPaymentOpsServer.received.last { it.path == "/internal/v1/admin/payments/$pay" }.signature!!
            lines.forEach { line ->
                line shouldNotContain "mid-demo-1"
                line shouldNotContain order
                line shouldNotContain TransactionJson.ORDER_PREVIEW
                line shouldNotContain pay
                line shouldNotContain signature.substringAfter("v1=")
            }
        }
    }
})

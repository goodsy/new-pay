package com.paycore.admin.paymentops

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
 * 화면 운영 홈 KPI HTTP(ADMIN-HOME-KPI-001 §4.2·§6·§8·T-K3-1~7 — 실제 2단계 로그인 세션, 실제 MySQL 감사 표, PAYMENT는 실제 HTTP 대역 — 같은 서명 채널): 미인증 401(PAYMENT 미호출),
 * VIEW 4역할 200(검사·재구성한 문서, `no-store`), UNMASK 단독·역할 없음 403(PAYMENT 미호출), 분당 창 429(거래 조회 창과 독립), PAYMENT 503·모양 위반 → 503 `PAYMENT_UNAVAILABLE`,
 * 감사 행 0, 로그·지표 label에 금액 없음. 컨텍스트는 `TransactionLookupHttpSpec`과 같은 구성(새 Spring 컨텍스트를 만들지 않는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@AdminDbTest
@Import(AdminAccountStorage::class, LoginReadyAccounts::class, ApprovalPath::class)
class HomeKpiHttpSpec(
    private val mockMvc: MockMvc,
    private val ready: LoginReadyAccounts,
    private val path: ApprovalPath,
    private val container: MySQLContainer,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    StubPaymentOpsServer.start()
    fun loggedIn(account: LoginReadyAccounts.LoginReady) = AdminHttpClient(mockMvc).also { it.login(account.loginName, account.password, account.secret).status shouldBe 200 }
    fun withRole(role: AdminRole) = ready.withoutRoles().also { path.grant(it.account.id, role) }
    fun audits(actorId: String) = AdminDbAccounts.root(container).use { it.column("SELECT action FROM admin_audit_event WHERE actor_id = ? ORDER BY event_id", actorId) }
    fun calls() = StubPaymentOpsServer.requestsForPath(StubPaymentOpsServer.HOME_KPI_PATH).size
    fun kpis(client: AdminHttpClient) = client.perform(get("/api/home-kpis"))
    fun counter(result: String) = registry.find("paycore.admin.ops.home_kpi").tag("result", result).counter()?.count() ?: 0.0

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

    afterEach { StubPaymentOpsServer.homeKpi(StubPaymentOpsServer.Reply(200, StubPaymentOpsServer.EMPTY_HOME_KPI)) }

    Given("T-K3-1 no session") {
        Then("401 and PAYMENT is not called") {
            val before = calls()
            AdminHttpClient(mockMvc).also { it.csrf() }.perform(get("/api/home-kpis")).status shouldBe 401
            calls() shouldBe before
        }
    }

    Given("T-K3-2 the four view roles") {
        Then("each gets 200 with the checked document (no-store), no audit row, and no amount in the logs") {
            val marker = "987654321"
            StubPaymentOpsServer.homeKpi(
                StubPaymentOpsServer.Reply(200, HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"$marker\"", cancelled = "\"0\"", net = "\"$marker\"", count = "3")))),
            )
            listOf(AdminRole.VIEWER, AdminRole.OPERATOR, AdminRole.APPROVER, AdminRole.SECURITY_ADMIN).forEach { role ->
                withClue(role.name) {
                    val account = withRole(role)
                    val client = loggedIn(account)
                    val ok = counter("OK")
                    val lines = captureLogs {
                        kpis(client).let {
                            it.status shouldBe 200
                            it.getHeader("Cache-Control")!! shouldContain "no-store"
                            it.contentAsString shouldBe HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"$marker\"", cancelled = "\"0\"", net = "\"$marker\"", count = "3")))
                        }
                    }
                    lines.forEach { it shouldNotContain marker }
                    counter("OK") shouldBe ok + 1
                    // T-K3-7: 집계 조회는 감사하지 않는다(로그인 감사 외 행 없음).
                    audits(account.account.id.value).filter { it.startsWith("PAYMENT") || it.contains("KPI") }.shouldBeEmpty()
                }
            }
        }
    }

    Given("T-K3-1 an UNMASK-only account and an account without roles") {
        Then("403 FORBIDDEN without a PAYMENT call") {
            listOf(withRole(AdminRole.UNMASK), ready.withoutRoles()).forEach { account ->
                val client = loggedIn(account)
                val before = calls()
                kpis(client).let {
                    it.status shouldBe 403
                    it.contentAsString shouldBe """{"error":"FORBIDDEN"}"""
                }
                calls() shouldBe before
            }
        }
    }

    Given("T-K3-3 the home KPI minute window (test value 4) and the transaction lookup window (test value 5)") {
        Then("the home KPI window runs out with 429 RATE_LIMITED while a transaction search of the same actor still reaches PAYMENT") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            // 분 경계에 걸리면 창이 새로 시작되므로 상한(4)의 두 배 + 2회 시도해 한 번 이상 429.
            val statuses = (1..10).map { kpis(client).status }
            (statuses.count { it == 429 } >= 1) shouldBe true
            statuses.filter { it != 429 }.toSet() shouldBe setOf(200)
            client.perform(post("/api/payment-searches").contentType(MediaType.APPLICATION_JSON).content("""{"createdFrom":"2026-11-01T00:00:00.000Z","createdTo":"2026-11-02T00:00:00.000Z"}"""))
                .status shouldBe 200
            kpis(client).let { if (it.status == 429) it.contentAsString shouldBe """{"error":"RATE_LIMITED"}""" }
        }
        // 리뷰 MINOR-3(rev 0.3a): 반대 방향 — 거래 조회 창(5)을 소진한 직후에도 같은 행위자의 KPI 호출은 PAYMENT까지 가서 200.
        Then("the reverse: right after the transaction lookup window answers 429, a home KPI call of the same actor still reaches PAYMENT with 200") {
            val client = loggedIn(withRole(AdminRole.OPERATOR))
            fun search() = client.perform(
                post("/api/payment-searches").contentType(MediaType.APPLICATION_JSON).content("""{"createdFrom":"2026-11-01T00:00:00.000Z","createdTo":"2026-11-02T00:00:00.000Z"}"""),
            )
            // 분 경계에 걸리면 창이 새로 시작되므로 첫 429가 나올 때까지(최대 상한의 두 배 + 2회) 시도하고, 429 바로 다음에 KPI를 부른다.
            val statuses = mutableListOf<Int>()
            while (statuses.size < 12 && statuses.lastOrNull() != 429) statuses += search().status
            withClue("transaction search statuses $statuses") {
                statuses.last() shouldBe 429
                statuses.dropLast(1).toSet() shouldBe setOf(200)
            }
            val before = calls()
            kpis(client).let {
                it.status shouldBe 200
                it.contentAsString shouldBe StubPaymentOpsServer.EMPTY_HOME_KPI
            }
            calls() shouldBe before + 1
        }
    }

    Given("T-K3-4·5 PAYMENT unavailable or a document failing the checks") {
        Then("503 PAYMENT_UNAVAILABLE (no cause in the body)") {
            val client = loggedIn(withRole(AdminRole.VIEWER))
            listOf(
                StubPaymentOpsServer.Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}"),
                StubPaymentOpsServer.Reply(200, HomeKpiJson.doc(items = listOf(HomeKpiJson.item(net = "\"-19999\"")))),
                StubPaymentOpsServer.Reply(200, HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "150000")))),
            ).forEach { reply ->
                StubPaymentOpsServer.homeKpi(reply)
                kpis(client).let {
                    it.status shouldBe 503
                    it.contentAsString shouldBe """{"error":"PAYMENT_UNAVAILABLE"}"""
                }
            }
        }
    }
})

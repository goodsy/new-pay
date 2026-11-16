package com.paycore.admin.paymentops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.paymentops.adapter.integration.AdminCallSigner
import com.paycore.admin.paymentops.adapter.integration.HttpPaymentOpsGateway
import com.paycore.admin.paymentops.adapter.integration.PaymentOpsSettings
import com.paycore.admin.paymentops.application.required.PaymentReadResult
import com.paycore.admin.support.StubPaymentOpsServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * 운영 홈 KPI gateway(ADMIN-HOME-KPI-001 §4.2·§4.3·T-K3-4·T-K3-5·T-K3-6 — **기존** ADMIN → PAYMENT 서명 채널, 실제 HTTP 대역 [StubPaymentOpsServer]): 서명 GET(대역이 앱과 독립으로
 * golden ② 시험 키 검증)·query 없음, 응답은 허용 필드 모양 + 이 요청의 답 검사(fail-closed — 위반 하나라도 Unavailable `RESPONSE_UNREADABLE`), PAYMENT 오류·timeout·과대 본문도
 * Unavailable, DB TX 안 호출 거부.
 */
class HomeKpiGatewaySpec : FunSpec({

    StubPaymentOpsServer.start()
    val key = "dGVzdC1vbmx5LWFkbWluLWNhbGwtZ29sZGVuLWtleS0wMDAx"
    fun settings(read: Duration = Duration.ofSeconds(2)) = PaymentOpsSettings(
        "http://127.0.0.1:${StubPaymentOpsServer.PORT}", "adm-golden-k1", key, Duration.ofSeconds(1), read, PaymentOpsSettings.Permits(4, 4, 4), 5, Duration.ofHours(24),
        Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofHours(24), Duration.ofMinutes(5), 50, Duration.ofMinutes(2), 10, Duration.ofDays(1),
    )
    fun gateway() = settings().let { s -> HttpPaymentOpsGateway(s, AdminCallSigner(s.keyId, s.signingKey())) { Instant.now() } }
    val mapper = JsonMapper.builder().build()

    afterEach { StubPaymentOpsServer.homeKpi(StubPaymentOpsServer.Reply(200, StubPaymentOpsServer.EMPTY_HOME_KPI)) }

    fun answer(body: String, status: Int = 200, delay: Long = 0): PaymentReadResult {
        StubPaymentOpsServer.homeKpi(StubPaymentOpsServer.Reply(status, body, delay))
        return gateway().homeKpis()
    }

    test("a valid document: signed GET without query the stub verifies independently; the document is rebuilt exactly (amounts stay decimal strings)") {
        val body = HomeKpiJson.doc()
        val found = answer(body).shouldBeInstanceOf<PaymentReadResult.Found>().document
        mapper.writeValueAsString(found) shouldBe mapper.writeValueAsString(mapper.readTree(body))
        val r = StubPaymentOpsServer.requestsForPath(StubPaymentOpsServer.HOME_KPI_PATH).last()
        listOf(r.method, r.query, r.signatureValid, r.body.size) shouldBe listOf("GET", null, true, 0)
        // 빈 날(asOf = from = KST 자정)과 2^53을 넘는 금액도 그대로.
        answer(HomeKpiJson.doc(items = emptyList(), asOf = HomeKpiJson.FROM, businessDate = "2026-11-15")).shouldBeInstanceOf<PaymentReadResult.Found>()
        val huge = HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"27670116110564327421\"", cancelled = "\"1\"", net = "\"27670116110564327420\"", count = "3")))
        (answer(huge).shouldBeInstanceOf<PaymentReadResult.Found>().document["items"] as List<*>).single().let { (it as Map<*, *>)["netApprovedAmountMinor"] } shouldBe
            "27670116110564327420"
    }

    test("T-K3-5 shape and this-request violations are Unavailable RESPONSE_UNREADABLE (fail-closed)") {
        val sixtyFive = (0 until 65).map { i ->
            val c = "\"" + ('A' + i / 26).toString() + ('A' + i % 26) + "X\""
            HomeKpiJson.item(environment = if (i < 33) "\"LIVE\"" else "\"TEST\"", currency = c, approved = "\"1\"", cancelled = "\"0\"", net = "\"1\"", count = "1")
        }
        val cases = linkedMapOf(
            "amount as a JSON number" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "150000"))),
            "net != approved - cancelled" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(net = "\"-19999\""))),
            "duplicate (environment, currency)" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(), HomeKpiJson.item())),
            "from not the KST midnight of businessDate" to HomeKpiJson.doc(from = "2026-11-14T15:00:00.001Z"),
            "businessDate not the KST date of asOf" to HomeKpiJson.doc(businessDate = "2026-11-16", from = "2026-11-15T15:00:00.000Z"),
            "unknown environment" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(environment = "\"STAGE\""))),
            "decimal point" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"150000.00\""))),
            "-0" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"5\"", cancelled = "\"5\"", net = "\"-0\"", count = "0"))),
            "leading zero" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"0150000\""))),
            "negative approved" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"-5\"", net = "\"-170005\""))),
            "time zone not the literal Asia/Seoul" to HomeKpiJson.doc(timeZone = "UTC"),
            "count without an approved sum" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"0\"", cancelled = "\"100\"", net = "\"-100\"", count = "1"))),
            "row without any fact" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(approved = "\"0\"", cancelled = "\"0\"", net = "\"0\"", count = "0"))),
            "asOf = from with items" to HomeKpiJson.doc(asOf = HomeKpiJson.FROM),
            "from after asOf" to HomeKpiJson.doc(asOf = "2026-11-14T14:59:59.999Z"),
            "TEST before LIVE" to HomeKpiJson.doc(items = listOf(HomeKpiJson.USD_TEST, HomeKpiJson.item())),
            "currency descending" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(currency = "\"USD\""), HomeKpiJson.item())),
            "65 items" to HomeKpiJson.doc(items = sixtyFive),
            "negative count" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(count = "-1"))),
            "count as a string" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(count = "\"2\""))),
            "fractional count" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(count = "2.5"))),
            "lower-case currency" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(currency = "\"krw\""))),
            "unknown item field" to HomeKpiJson.doc(items = listOf(HomeKpiJson.item(extra = ",\"merchantAccountId\":\"mid-1\""))),
            "unknown top-level field" to HomeKpiJson.doc(extra = ",\"settlementAmount\":\"1\""),
            "missing items" to "{\"asOf\":\"${HomeKpiJson.AS_OF}\",\"businessDate\":\"${HomeKpiJson.BUSINESS_DATE}\",\"timeZone\":\"Asia/Seoul\",\"from\":\"${HomeKpiJson.FROM}\"}",
            "malformed asOf" to HomeKpiJson.doc(asOf = "2026-11-15T05:12:33Z"),
            "not JSON" to "approved 150000",
        )
        // 대조군: 65개 시험의 행 자체는 유효하다(64개면 통과).
        answer(HomeKpiJson.doc(items = sixtyFive.take(64))).shouldBeInstanceOf<PaymentReadResult.Found>()
        cases.forEach { (name, body) -> withClue(name) { answer(body) shouldBe PaymentReadResult.Unavailable("RESPONSE_UNREADABLE") } }
    }

    test("T-K3-4 PAYMENT 503, 401, 404, a timeout and an oversized body are Unavailable") {
        answer("{\"error\":\"TEMPORARILY_UNAVAILABLE\"}", 503) shouldBe PaymentReadResult.Unavailable("HTTP_503")
        answer("{\"error\":\"ADMIN_READ_BUSY\"}", 503) shouldBe PaymentReadResult.Unavailable("HTTP_503")
        answer("{\"error\":\"UNAUTHENTICATED\"}", 401) shouldBe PaymentReadResult.Unavailable("HTTP_401")
        // 리뷰 NIT-1(rev 0.3a): 이 경로의 404는 실제 결과(Unavailable HTTP_404)로 기록한다 — "NotFound" 로그 줄이 남지 않는다.
        val logger = LoggerFactory.getLogger(HttpPaymentOpsGateway::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            answer("{\"error\":\"PAYMENT_NOT_FOUND\"}", 404) shouldBe PaymentReadResult.Unavailable("HTTP_404")
        } finally {
            logger.detachAppender(appender)
        }
        appender.list.map { it.formattedMessage }.filter { it.startsWith("operation=payment-ops-read-home-kpi") } shouldBe
            listOf("operation=payment-ops-read-home-kpi result=HTTP_404")
        answer(HomeKpiJson.doc(), delay = 2_500) shouldBe PaymentReadResult.Unavailable("TIMEOUT")
        answer(HomeKpiJson.doc(extra = ",\"pad\":\"" + "x".repeat(PaymentOpsSettings.RESPONSE_MAX_BYTES) + "\"")) shouldBe PaymentReadResult.Unavailable("RESPONSE_UNREADABLE")
    }

    test("T-K3-6 a call inside a database transaction is refused before PAYMENT is called") {
        val before = StubPaymentOpsServer.requestsForPath(StubPaymentOpsServer.HOME_KPI_PATH).size
        TransactionSynchronizationManager.setActualTransactionActive(true)
        try {
            shouldThrow<IllegalStateException> { gateway().homeKpis() }
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false)
        }
        StubPaymentOpsServer.requestsForPath(StubPaymentOpsServer.HOME_KPI_PATH).size shouldBe before
    }
})

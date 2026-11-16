package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.web.InternalConnector
import com.paycore.payment.payment.adapter.web.MutablePaymentClock
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.CancelFixture
import com.paycore.payment.payment.application.ConfirmFixture
import com.paycore.payment.payment.application.ProgrammableVan
import com.paycore.payment.payment.application.ScriptedAdmissionPort
import com.paycore.payment.payment.application.provided.CancelPaymentResult
import com.paycore.payment.payment.application.provided.CancelPaymentUseCase
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Money
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 운영 홈 KPI ADMIN 채널(ADMIN-HOME-KPI-001 §4.1·T-K2-1·T-K2-3 — 실제 보안 필터 체인 + 내부 connector + 실제 MySQL): 서명 없음·위조·시각 창 밖 401(원인 비구별), query 문자열 400,
 * 200 본문 바이트(키 순서·금액 10진 문자열·approvedCount 정수)·`no-store`. 다른 시험과 겹치지 않는 KST 날짜(2300년 이후)를 써서 정확한 본문을 단언한다.
 */
@PaymentApiTest
class AdminHomeKpiApiSpec(
    private val internal: InternalConnector,
    private val cancel: CancelPaymentUseCase,
    private val clock: MutablePaymentClock,
    db: DbAccess,
    confirm: ConfirmPaymentUseCase,
    van: ProgrammableVan,
    admission: ScriptedAdmissionPort,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val api = AdminOpsTestClient(internal.localPort)
    val fixture = CancelFixture(ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db), confirm, admission, controls, payments, transactionManager, van)
    val path = "/internal/v1/admin/home-kpis"
    val seoul = ZoneId.of("Asia/Seoul")
    val firstDay = LocalDate.of(2300, 1, 1).plusDays((System.nanoTime() and 0x3FFF) * 2)

    afterEach { clock.reset() }

    fun raw(header: String?, target: String = path): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create("http://localhost:${internal.localPort}$target")).timeout(Duration.ofSeconds(30)).GET()
        header?.let { b.header(AdminCallSignature.HEADER, it) }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    Given("T-K2-1 the home KPI admin path") {
        Then("unsigned, forged (signed for another path) and out-of-window signatures are 401 UNAUTHENTICATED alike; a query string is 400 QUERY_NOT_ALLOWED") {
            val unsigned = raw(null)
            val forged = raw(AdminChannelFixture.header("GET", "/internal/v1/admin/payments/x", ByteArray(0)))
            val stale = raw(AdminChannelFixture.header("GET", path, ByteArray(0), t = Instant.now().minus(Duration.ofHours(1)).epochSecond))
            listOf(unsigned, forged, stale).forEach {
                it.statusCode() shouldBe 401
                it.body() shouldBe """{"error":"UNAUTHENTICATED"}"""
            }
            api.get("$path?x=1").let {
                it.status shouldBe 400
                it.body shouldBe """{"error":"QUERY_NOT_ALLOWED"}"""
            }
        }
    }

    Given("T-K2-3 a day with one approval of 10,000 and a partial cancel of 3,000") {
        Then("200 with the exact body bytes (key order, decimal strings, integer count) and no-store") {
            val d = firstDay.atStartOfDay(seoul).toInstant()
            clock.set(d.plus(Duration.ofHours(1)))
            val a = fixture.authorized(base = d.plus(Duration.ofHours(1)))
            clock.set(d.plus(Duration.ofHours(2)))
            cancel.cancel(a.command(money = Money(3_000, a.money.currency))).shouldBeInstanceOf<CancelPaymentResult.Accepted>()
            clock.set(d.plus(Duration.ofHours(10)).plusMillis(123))
            val r = raw(AdminChannelFixture.header("GET", path, ByteArray(0)))
            r.statusCode() shouldBe 200
            r.headers().firstValue("Cache-Control").orElse("") shouldContain "no-store"
            val asOf = d.plus(Duration.ofHours(10)).plusMillis(123)
            r.body() shouldBe "{\"asOf\":\"${AdminChannelResponses.time(asOf)}\",\"businessDate\":\"$firstDay\",\"timeZone\":\"Asia/Seoul\"," +
                "\"from\":\"${AdminChannelResponses.time(d)}\",\"items\":[{\"environment\":\"TEST\",\"currency\":\"KRW\",\"approvedAmountMinor\":\"10000\"," +
                "\"cancelledAmountMinor\":\"3000\",\"netApprovedAmountMinor\":\"7000\",\"approvedCount\":1}]}"
        }
        Then("a day without facts is 200 with an empty items list") {
            val d = firstDay.plusDays(1).atStartOfDay(seoul).toInstant()
            clock.set(d.plus(Duration.ofHours(5)))
            api.get(path).let {
                it.status shouldBe 200
                it.body shouldBe "{\"asOf\":\"${AdminChannelResponses.time(d.plus(Duration.ofHours(5)))}\",\"businessDate\":\"${firstDay.plusDays(1)}\",\"timeZone\":\"Asia/Seoul\"," +
                    "\"from\":\"${AdminChannelResponses.time(d)}\",\"items\":[]}"
            }
        }
    }
})

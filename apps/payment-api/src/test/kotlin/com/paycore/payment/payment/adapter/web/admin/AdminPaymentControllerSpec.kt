package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.application.provided.AdminPaymentDetail
import com.paycore.payment.payment.application.provided.AdminPaymentPage
import com.paycore.payment.payment.application.provided.AdminPaymentQuery
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.mock.web.MockHttpServletRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 관리자 거래 조회 진입 컨트롤러(TXN-OPS-001 §3 — 컨트롤러 직접, 대역 Query): 조회 permit 소진 즉시 503 `ADMIN_READ_BUSY` + `busy{endpoint}`(Concurrency Control),
 * 저장소 실패·질의 시간 초과 503 `TEMPORARILY_UNAVAILABLE`(시간 초과는 `query_timeout{endpoint}`) — 예외 문구(바인딩 값일 수 있다)는 로그에 없다(Recoverability·Observability),
 * 형식 위반 본문 400·형식 아닌 paymentId 404는 Query를 부르지 않는다.
 */
class AdminPaymentControllerSpec : BehaviorSpec({

    val pay = "pay_01JC0000000000000000000001"
    val canary = "SELECT order_name FROM payment WHERE merchant_order_id = 'LEAKCANARY-order-77'"

    class Fixture(val failure: RuntimeException? = null) {
        val registry = SimpleMeterRegistry()
        val permits = AdminChannelPermits(AdminOpsSettings(readPermits = 1, commandPermits = 1, publicPoolReserve = 1), registry)
        val calls = mutableListOf<String>()
        val controller = AdminPaymentController(
            object : AdminPaymentQuery {
                override fun search(criteria: AdminPaymentSearchCriteria): AdminPaymentPage {
                    calls += "search"
                    failure?.let { throw it }
                    return AdminPaymentPage(emptyList(), null)
                }

                override fun detail(paymentId: String): AdminPaymentDetail? {
                    calls += "detail"
                    failure?.let { throw it }
                    return null
                }
            },
            permits, registry,
        )

        fun search(body: String) = controller.search(MockHttpServletRequest("POST", "/internal/v1/admin/payment-searches").apply { setContent(body.toByteArray()) })
        fun detail(id: String) = controller.detail(id, MockHttpServletRequest("GET", "/internal/v1/admin/payments/$id"))
        fun count(name: String, endpoint: String) = registry.find(name).tag("endpoint", endpoint).counter()?.count() ?: 0.0
    }

    val body = """{"paymentId":"$pay"}"""

    Given("every read permit held") {
        Then("Concurrency Control: both endpoints are 503 ADMIN_READ_BUSY at once, without calling the query, and counted per endpoint") {
            val f = Fixture()
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { f.permits.read("hold", { Unit }) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }.also { it.start() }
            held.await(10, TimeUnit.SECONDS) shouldBe true
            try {
                f.search(body).let {
                    it.statusCode.value() shouldBe 503
                    String(it.body!!) shouldBe """{"error":"ADMIN_READ_BUSY"}"""
                }
                f.detail(pay).statusCode.value() shouldBe 503
            } finally {
                release.countDown()
                holder.join()
            }
            f.calls.shouldBeEmpty()
            f.count(AdminChannelPermits.BUSY_METRIC, "payment_search") shouldBe 1.0
            f.count(AdminChannelPermits.BUSY_METRIC, "payment_detail") shouldBe 1.0
            f.search(body).statusCode.value() shouldBe 200
        }
    }

    Given("a storage failure and a query timeout") {
        Then("Recoverability: each is 503 TEMPORARILY_UNAVAILABLE; a timeout is counted per endpoint; the exception message is never logged") {
            listOf(OperatorQueryFailure.Unavailable(IllegalStateException(canary)), IllegalStateException(canary), OperatorQueryFailure.TimedOut(IllegalStateException(canary))).forEach { e ->
                val f = Fixture(e)
                val lines = CapturedLogs().use { logs ->
                    f.search(body).let {
                        it.statusCode.value() shouldBe 503
                        String(it.body!!) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                    }
                    f.detail(pay).let {
                        it.statusCode.value() shouldBe 503
                        String(it.body!!) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                    }
                    logs.lines()
                }
                (lines.size >= 2) shouldBe true
                lines.forEach { it shouldNotContain "LEAKCANARY" }
                val timeouts = if (e is OperatorQueryFailure.TimedOut) 1.0 else 0.0
                f.count("paycore.payment.admin_channel.query_timeout", "payment_search") shouldBe timeouts
                f.count("paycore.payment.admin_channel.query_timeout", "payment_detail") shouldBe timeouts
            }
        }
    }

    Given("malformed input") {
        Then("a malformed body is 400 REQUEST_MALFORMED and a malformed paymentId 404 PAYMENT_NOT_FOUND, both without calling the query") {
            val f = Fixture()
            f.search("""{"paymentId":"pay_x"}""").let {
                it.statusCode.value() shouldBe 400
                String(it.body!!) shouldBe """{"error":"REQUEST_MALFORMED"}"""
            }
            f.detail("pay_x").let {
                it.statusCode.value() shouldBe 404
                String(it.body!!) shouldBe """{"error":"PAYMENT_NOT_FOUND"}"""
            }
            f.calls.shouldBeEmpty()
        }
    }
})

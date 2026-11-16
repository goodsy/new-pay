package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.adapter.web.CapturedLogs
import com.paycore.payment.payment.application.provided.HomeKpiItem
import com.paycore.payment.payment.application.provided.HomeKpiQuery
import com.paycore.payment.payment.application.provided.HomeKpiSnapshot
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import com.paycore.payment.payment.domain.HomeKpiFigures
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.mock.web.MockHttpServletRequest
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 운영 홈 KPI 진입 컨트롤러(ADMIN-HOME-KPI-001 §4.1·§8·T-K2-2·T-K2-3 — 컨트롤러 직접, 대역 Query): 조회 permit 소진 즉시 503 `ADMIN_READ_BUSY` + `busy{endpoint=home_kpi}`(Query 미호출),
 * 질의 시간 초과 503 `TEMPORARILY_UNAVAILABLE` + `query_timeout{endpoint=home_kpi}`, 저장소 실패·불변식 위반 503(500 없음), 예외 문구·금액은 로그에 없다. 200 본문 금액은 10진 문자열
 * (Long 범위 밖·음수 정확), 처리 시간 timer `paycore.payment.admin_channel.home_kpi.duration`.
 */
class AdminHomeKpiControllerSpec : BehaviorSpec({

    val canary = "SELECT SUM(amount_minor) FROM payment_transaction -- LEAKCANARY 98765"

    class Fixture(val failure: RuntimeException? = null, val snapshot: HomeKpiSnapshot? = null) {
        val registry = SimpleMeterRegistry()
        val permits = AdminChannelPermits(AdminOpsSettings(readPermits = 1, commandPermits = 1, publicPoolReserve = 1), registry)
        var calls = 0
        val controller = AdminHomeKpiController(
            object : HomeKpiQuery {
                override fun today(): HomeKpiSnapshot {
                    calls++
                    failure?.let { throw it }
                    return checkNotNull(snapshot)
                }
            },
            permits, registry,
        )

        fun call() = controller.today(MockHttpServletRequest("GET", "/internal/v1/admin/home-kpis"))
        fun count(name: String) = registry.find(name).tag("endpoint", "home_kpi").counter()?.count() ?: 0.0
    }

    val big = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3))
    val snapshot = HomeKpiSnapshot(
        Instant.parse("2026-11-15T05:12:33.123Z"), LocalDate.parse("2026-11-15"), "Asia/Seoul", Instant.parse("2026-11-14T15:00:00.000Z"),
        listOf(
            HomeKpiItem("LIVE", "KRW", HomeKpiFigures(BigInteger.valueOf(150_000), BigInteger.valueOf(170_000), 2)),
            HomeKpiItem("TEST", "USD", HomeKpiFigures(big, BigInteger.ZERO, 1)),
        ),
    )

    Given("T-K2-3 a snapshot with a negative net and a sum beyond Long") {
        Then("200 with the fixed key order, decimal strings for amounts, an integer count, and the duration timer recorded") {
            val f = Fixture(snapshot = snapshot)
            val r = f.call()
            r.statusCode.value() shouldBe 200
            String(r.body!!) shouldBe "{\"asOf\":\"2026-11-15T05:12:33.123Z\",\"businessDate\":\"2026-11-15\",\"timeZone\":\"Asia/Seoul\",\"from\":\"2026-11-14T15:00:00.000Z\"," +
                "\"items\":[{\"environment\":\"LIVE\",\"currency\":\"KRW\",\"approvedAmountMinor\":\"150000\",\"cancelledAmountMinor\":\"170000\",\"netApprovedAmountMinor\":\"-20000\"," +
                "\"approvedCount\":2},{\"environment\":\"TEST\",\"currency\":\"USD\",\"approvedAmountMinor\":\"27670116110564327421\",\"cancelledAmountMinor\":\"0\"," +
                "\"netApprovedAmountMinor\":\"27670116110564327421\",\"approvedCount\":1}]}"
            r.headers.cacheControl shouldBe "no-store"
            f.registry.find("paycore.payment.admin_channel.home_kpi.duration").timer()!!.count() shouldBe 1L
        }
    }

    Given("T-K2-2 every read permit held") {
        Then("503 ADMIN_READ_BUSY at once without calling the query, counted as busy{endpoint=home_kpi}") {
            val f = Fixture(snapshot = snapshot)
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = Thread { f.permits.read("hold", { Unit }) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }.also { it.start() }
            held.await(10, TimeUnit.SECONDS) shouldBe true
            try {
                f.call().let {
                    it.statusCode.value() shouldBe 503
                    String(it.body!!) shouldBe """{"error":"ADMIN_READ_BUSY"}"""
                }
            } finally {
                release.countDown()
                holder.join()
            }
            f.calls shouldBe 0
            f.count(AdminChannelPermits.BUSY_METRIC) shouldBe 1.0
            f.call().statusCode.value() shouldBe 200
        }
    }

    Given("T-K2-2 a query timeout, a storage failure and an invariant violation") {
        Then("each is 503 TEMPORARILY_UNAVAILABLE (never 500); only the timeout is counted as query_timeout{endpoint=home_kpi}; no exception text or amount is logged") {
            listOf(
                OperatorQueryFailure.TimedOut(IllegalStateException(canary)), OperatorQueryFailure.Unavailable(IllegalStateException(canary)), IllegalArgumentException(canary),
            ).forEach { e ->
                val f = Fixture(e)
                val lines = CapturedLogs().use { logs ->
                    f.call().let {
                        it.statusCode.value() shouldBe 503
                        String(it.body!!) shouldBe """{"error":"TEMPORARILY_UNAVAILABLE"}"""
                    }
                    logs.lines()
                }
                (lines.isNotEmpty()) shouldBe true
                lines.forEach {
                    it shouldNotContain "LEAKCANARY"
                    it shouldNotContain "98765"
                }
                f.count("paycore.payment.admin_channel.query_timeout") shouldBe (if (e is OperatorQueryFailure.TimedOut) 1.0 else 0.0)
            }
        }
    }
})

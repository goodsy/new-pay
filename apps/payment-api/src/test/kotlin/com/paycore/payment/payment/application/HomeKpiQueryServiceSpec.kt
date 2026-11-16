package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.HomeKpiItem
import com.paycore.payment.payment.application.provided.OperatorQueryFailure
import com.paycore.payment.payment.application.required.HomeKpiReader
import com.paycore.payment.payment.application.required.PaymentClock
import com.paycore.payment.payment.application.required.PersistenceFailure
import com.paycore.payment.payment.application.required.QueryTimedOut
import com.paycore.payment.payment.domain.HomeKpiFigures
import com.paycore.payment.payment.domain.HomeKpiWindow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

/**
 * 운영 홈 KPI 조회 서비스(ADMIN-HOME-KPI-001 §3.1·§3.4·§5 — 대역 Reader·고정 시계): asOf는 PAYMENT 시계 한 번(밀리초 절사), KST 자정 정각은 Reader를 부르지 않는 빈 목록, 정렬은
 * application(LIVE → TEST, currency 오름차순), 같은 행 키 중복은 fail-closed, 저장소 실패·시간 초과는 기존 관리 조회 분류.
 */
class HomeKpiQueryServiceSpec : BehaviorSpec({

    fun clock(at: String) = object : PaymentClock {
        override fun now(): Instant = Instant.parse(at)
    }

    fun item(environment: String, currency: String, approved: Long = 1_000) =
        HomeKpiItem(environment, currency, HomeKpiFigures(BigInteger.valueOf(approved), BigInteger.ZERO, 1))

    class RecordingReader(private val rows: List<HomeKpiItem> = emptyList(), private val failure: RuntimeException? = null) : HomeKpiReader {
        val windows = mutableListOf<HomeKpiWindow>()
        override fun read(window: HomeKpiWindow): List<HomeKpiItem> {
            windows += window
            failure?.let { throw it }
            return rows
        }
    }

    Given("a reader returning rows in arbitrary order") {
        Then("the snapshot carries asOf (ms), businessDate, the literal time zone and from; items are LIVE first then TEST, currency ascending") {
            val reader = RecordingReader(listOf(item("TEST", "USD"), item("LIVE", "USD"), item("TEST", "KRW"), item("LIVE", "KRW")))
            val s = HomeKpiQueryService(reader, clock("2026-11-15T05:12:33.123456Z")).today()
            s.asOf shouldBe Instant.parse("2026-11-15T05:12:33.123Z")
            s.businessDate shouldBe LocalDate.parse("2026-11-15")
            s.timeZone shouldBe "Asia/Seoul"
            s.from shouldBe Instant.parse("2026-11-14T15:00:00.000Z")
            s.items.map { it.environment + "/" + it.currency } shouldBe listOf("LIVE/KRW", "LIVE/USD", "TEST/KRW", "TEST/USD")
            reader.windows shouldBe listOf(HomeKpiWindow.of(Instant.parse("2026-11-15T05:12:33.123Z")))
        }
    }

    Given("asOf exactly at the KST midnight (T-K1-2)") {
        Then("the items are empty and the reader is not called") {
            val reader = RecordingReader(listOf(item("LIVE", "KRW")))
            val s = HomeKpiQueryService(reader, clock("2026-11-15T15:00:00.000Z")).today()
            s.items.shouldBeEmpty()
            s.asOf shouldBe s.from
            reader.windows.shouldBeEmpty()
        }
    }

    Given("a reader returning the same (environment, currency) twice") {
        Then("the query fails closed instead of choosing one row") {
            shouldThrow<IllegalStateException> {
                HomeKpiQueryService(RecordingReader(listOf(item("LIVE", "KRW"), item("LIVE", "KRW", 2_000))), clock("2026-11-15T05:00:00Z")).today()
            }
        }
    }

    Given("reader failures") {
        Then("a query timeout is OperatorQueryFailure.TimedOut and a storage failure OperatorQueryFailure.Unavailable") {
            shouldThrow<OperatorQueryFailure.TimedOut> { HomeKpiQueryService(RecordingReader(failure = QueryTimedOut(null)), clock("2026-11-15T05:00:00Z")).today() }
            shouldThrow<OperatorQueryFailure.Unavailable> {
                HomeKpiQueryService(RecordingReader(failure = PersistenceFailure.Unavailable(IllegalStateException("x"))), clock("2026-11-15T05:00:00Z")).today()
            }
        }
    }
})

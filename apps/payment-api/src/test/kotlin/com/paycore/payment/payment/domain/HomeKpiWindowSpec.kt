package com.paycore.payment.payment.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.math.BigInteger
import io.kotest.assertions.throwables.shouldThrow
import java.time.Instant
import java.time.LocalDate

/**
 * 운영 홈 KPI 구간·값(ADMIN-HOME-KPI-001 §3.1–§3.3, T-K1-2 단위). 기대값은 명세에서 직접 적는다: Asia/Seoul 날짜, [KST 00:00, asOf), 밀리초 절사, 자정 정각 = 빈 구간,
 * net = approved − cancelled(음수 허용), 음수·사실 없는 행·승인 없는 건수 거부.
 */
class HomeKpiWindowSpec : BehaviorSpec({

    Given("asOf instants around the Asia/Seoul midnight (T-K1-2)") {
        Then("asOf 14:59:59.999Z is still the same KST day; its window starts at the previous day 15:00Z") {
            val w = HomeKpiWindow.of(Instant.parse("2026-11-15T14:59:59.999Z"))
            w.businessDate shouldBe LocalDate.parse("2026-11-15")
            w.from shouldBe Instant.parse("2026-11-14T15:00:00.000Z")
            w.asOf shouldBe Instant.parse("2026-11-15T14:59:59.999Z")
            w.isEmpty shouldBe false
        }
        Then("asOf exactly KST 00:00:00.000 is a new day with an empty window [from, from)") {
            val w = HomeKpiWindow.of(Instant.parse("2026-11-15T15:00:00.000Z"))
            w.businessDate shouldBe LocalDate.parse("2026-11-16")
            w.from shouldBe Instant.parse("2026-11-15T15:00:00.000Z")
            w.isEmpty shouldBe true
        }
        Then("one millisecond after the KST midnight is not empty") {
            HomeKpiWindow.of(Instant.parse("2026-11-15T15:00:00.001Z")).isEmpty shouldBe false
        }
        Then("asOf is truncated to milliseconds (sub-millisecond digits never reach SQL literals)") {
            val w = HomeKpiWindow.of(Instant.parse("2026-11-15T05:12:33.123987654Z"))
            w.asOf shouldBe Instant.parse("2026-11-15T05:12:33.123Z")
            HomeKpiWindow.of(Instant.parse("2026-11-15T15:00:00.000999Z")).isEmpty shouldBe true
        }
        Then("the zone is the literal Asia/Seoul") {
            HomeKpiWindow.ZONE.id shouldBe "Asia/Seoul"
        }
    }

    Given("figures of one (environment, currency) row") {
        fun big(v: Long) = BigInteger.valueOf(v)
        Then("net = approved − cancelled and may be negative (yesterday's approval cancelled today)") {
            HomeKpiFigures(big(10_000), big(3_000), 1).netApprovedMinor shouldBe big(7_000)
            HomeKpiFigures(big(0), big(5_000), 0).netApprovedMinor shouldBe big(-5_000)
            HomeKpiFigures(big(10_000), big(10_000), 0).netApprovedMinor shouldBe big(0)
        }
        Then("values beyond Long are exact") {
            val max = big(Long.MAX_VALUE)
            HomeKpiFigures(max.multiply(big(3)), max, 3).netApprovedMinor shouldBe max.multiply(big(2))
        }
        Then("negative sums or counts, a count without an approved sum, a count above the approved minor sum and a row without any fact are refused") {
            listOf(
                { HomeKpiFigures(big(-1), big(0), 0) },
                { HomeKpiFigures(big(1), big(-1), 0) },
                { HomeKpiFigures(big(1), big(0), -1) },
                { HomeKpiFigures(big(0), big(5), 1) },
                { HomeKpiFigures(big(2), big(0), 3) },
                { HomeKpiFigures(big(0), big(0), 0) },
            ).forEach { shouldThrow<IllegalArgumentException> { it() } }
        }
    }
})

package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.limitPolicy
import com.paycore.payment.payment.domain.AuthorizationFixture.usageKey
import com.paycore.payment.payment.domain.MoneyFixture.KRW
import com.paycore.payment.payment.domain.MoneyFixture.USD
import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.time.LocalDate

/**
 * 거래 한도(D-CPC-008·D-AUTH-006·D-AUTH-007, 명세 §4.5). Demo 한도: 건당 1,000,000원, 일 5,000,000원. 기대값은 명세에서 직접 적는다.
 * 동시 점유의 직렬화(행 잠금)는 AUTH-001 실DB 시험의 몫이며 이 시험은 단일 스레드 합계 불변식 증거다.
 */
class TransactionLimitSpec : BehaviorSpec({

    fun auth(n: Int) = AuthorizationId("demo-auth-%04d".format(n))

    Given("limit policy candidates for MID + currency (exactly one applies, fail-closed)") {
        val mid = OrderFixture.demoMid
        Then("none → Missing, one → Resolved, two → Ambiguous; another MID or currency does not count") {
            TransactionLimitPolicies.resolve(emptyList(), mid, KRW) shouldBe LimitPolicyResolution.Missing
            val krw = limitPolicy()
            val usd = krw.copy(version = "demo-limit-usd", currency = USD)
            val otherMid = krw.copy(version = "demo-limit-other", merchantAccountId = MerchantAccountId("demo-mid-002"))
            TransactionLimitPolicies.resolve(listOf(krw, usd, otherMid), mid, KRW) shouldBe LimitPolicyResolution.Resolved(krw)
            TransactionLimitPolicies.resolve(listOf(krw, krw.copy(version = "demo-limit-v2")), mid, KRW) shouldBe LimitPolicyResolution.Ambiguous
        }
        Then("unlimited is only an explicit value; a negative limit is invalid") {
            LimitAmount.Unlimited.shouldBeInstanceOf<LimitAmount>()
            shouldThrow<IllegalArgumentException> { LimitAmount.Limited(-1) }
        }
    }

    Given("the business date of the daily limit") {
        Then("it is the Asia/Seoul date of the authorization start (KST midnight boundary)") {
            LimitBusinessDate.of(Instant.parse("2026-10-15T14:59:59.999Z")) shouldBe LocalDate.of(2026, 10, 15)
            LimitBusinessDate.of(Instant.parse("2026-10-15T15:00:00Z")) shouldBe LocalDate.of(2026, 10, 16)
        }
    }

    Given("a daily usage with the Demo policy") {
        Then("per-transaction limit: equal is allowed, one more is rejected (checked before the daily limit)") {
            val u = DailyLimitUsage.open(usageKey())
            u.rejectionFor(1_000_000L.won(), limitPolicy()) shouldBe null
            u.rejectionFor(1_000_001L.won(), limitPolicy()) shouldBe LimitRejection.PER_TRANSACTION_LIMIT_EXCEEDED
        }
        Then("daily limit counts RESERVED + CONSUMED; the exact remainder fits, one more does not") {
            val u = DailyLimitUsage.open(usageKey())
            (1..4).forEach { u.reserve(auth(it), 1_000_000L.won(), limitPolicy()) }
            u.consume(auth(1))
            u.occupiedMinor shouldBe 4_000_000L
            u.rejectionFor(1_000_000L.won(), limitPolicy()) shouldBe null
            u.reserve(auth(5), 999_999L.won(), limitPolicy())
            u.rejectionFor(2L.won(), limitPolicy()) shouldBe LimitRejection.DAILY_LIMIT_EXCEEDED
            u.rejectionFor(1L.won(), limitPolicy()) shouldBe null
            shouldThrow<LimitExceededException> { u.reserve(auth(6), 2L.won(), limitPolicy()) }.reason shouldBe LimitRejection.DAILY_LIMIT_EXCEEDED
            u.reservationOf(auth(6)) shouldBe null
        }
        Then("a released reservation frees the limit; a consumed one is never restored (cancel/refund does not restore, D-AUTH-007)") {
            val u = DailyLimitUsage.open(usageKey())
            u.reserve(auth(1), 1_000_000L.won(), limitPolicy(daily = LimitAmount.Limited(1_000_000)))
            u.rejectionFor(1L.won(), limitPolicy(daily = LimitAmount.Limited(1_000_000))) shouldBe LimitRejection.DAILY_LIMIT_EXCEEDED
            u.release(auth(1))
            u.release(auth(1)) // 멱등
            u.occupiedMinor shouldBe 0L
            shouldThrow<IllegalStateException> { u.consume(auth(1)) }
            u.reserve(auth(2), 1_000_000L.won(), limitPolicy(daily = LimitAmount.Limited(1_000_000)))
            u.consume(auth(2))
            u.consume(auth(2)) // 멱등
            shouldThrow<IllegalStateException> { u.release(auth(2)) }
            u.reservationOf(auth(2))?.state shouldBe LimitReservationState.CONSUMED
        }
        Then("unlimited per-transaction and daily allow any amount but still record the reservation") {
            val u = DailyLimitUsage.open(usageKey())
            val unlimited = limitPolicy(LimitAmount.Unlimited, LimitAmount.Unlimited)
            u.reserve(auth(1), Long.MAX_VALUE.won(), unlimited)
            u.reservationOf(auth(1))?.state shouldBe LimitReservationState.RESERVED
        }
        Then("a zero limit rejects every amount") {
            DailyLimitUsage.open(usageKey()).rejectionFor(1L.won(), limitPolicy(daily = LimitAmount.Limited(0))) shouldBe
                LimitRejection.DAILY_LIMIT_EXCEEDED
        }
        Then("the same authorization cannot reserve twice; a different scope or currency is a caller defect") {
            val u = DailyLimitUsage.open(usageKey())
            u.reserve(auth(1), 10L.won(), limitPolicy())
            shouldThrow<IllegalStateException> { u.reserve(auth(1), 10L.won(), limitPolicy()) }
            shouldThrow<IllegalArgumentException> { u.rejectionFor(10L.usdCents(), limitPolicy()) }
            shouldThrow<IllegalArgumentException> {
                u.rejectionFor(10L.won(), limitPolicy().copy(merchantAccountId = MerchantAccountId("demo-mid-002")))
            }
        }
        Then("a released reservation re-occupied after a late approval becomes FORCED: recorded even over the limit, marked, and counted in the blocking sum (D-AUTH-010)") {
            val daily = limitPolicy(daily = LimitAmount.Limited(1_500_000))
            val u = DailyLimitUsage.open(usageKey())
            u.reserve(auth(1), 1_000_000L.won(), daily)
            u.release(auth(1))
            u.reserve(auth(2), 1_000_000L.won(), daily)
            u.reoccupyAfterLateApproval(auth(1), daily)
            u.reservationOf(auth(1)) shouldBe LimitReservation(auth(1), 1_000_000, LimitReservationState.FORCED, LimitExcess.EXCEEDED)
            u.occupiedMinor shouldBe 2_000_000L
            u.forcedMinor shouldBe 1_000_000L
            u.rejectionFor(1L.won(), daily) shouldBe LimitRejection.DAILY_LIMIT_EXCEEDED
            // 이미 기록된(RESERVED·CONSUMED·FORCED) 점유에는 효과 없음.
            u.reoccupyAfterLateApproval(auth(1), daily)
            u.reoccupyAfterLateApproval(auth(2), daily)
            u.reservationOf(auth(2))?.state shouldBe LimitReservationState.RESERVED
            // 승인 결과 경로(consume/release)는 FORCED를 바꾸지 않는다 — hold 해제 판정만 정리한다.
            shouldThrow<IllegalStateException> { u.consume(auth(1)) }
            shouldThrow<IllegalStateException> { u.release(auth(1)) }
        }
        Then("hold release findings settle a FORCED reservation: NO_EFFECT → RELEASED, REAL or DOUBLE → CONSUMED; other states unchanged") {
            HoldReleaseFinding.entries.forEach { finding ->
                val u = DailyLimitUsage.open(usageKey())
                u.reserve(auth(1), 10L.won(), limitPolicy())
                u.release(auth(1))
                u.reoccupyAfterLateApproval(auth(1), null)
                u.reservationOf(auth(1))?.excess shouldBe LimitExcess.UNDETERMINED
                u.settleForced(auth(1), finding)
                u.reservationOf(auth(1)) shouldBe LimitReservation(
                    auth(1), 10, if (finding == HoldReleaseFinding.NO_EFFECT_CONFIRMED) LimitReservationState.RELEASED else LimitReservationState.CONSUMED,
                )
            }
            val u = DailyLimitUsage.open(usageKey())
            u.reserve(auth(1), 10L.won(), limitPolicy())
            u.settleForced(auth(1), HoldReleaseFinding.REAL_APPROVAL_CONFIRMED)
            u.reservationOf(auth(1))?.state shouldBe LimitReservationState.RESERVED
        }
        Then("excess is recorded exactly for FORCED reservations") {
            shouldThrow<IllegalArgumentException> { LimitReservation(auth(1), 1, LimitReservationState.FORCED) }
            shouldThrow<IllegalArgumentException> { LimitReservation(auth(1), 1, LimitReservationState.RESERVED, LimitExcess.WITHIN) }
        }
        Then("sums never overflow silently") {
            val u = DailyLimitUsage.open(usageKey())
            val unlimited = limitPolicy(LimitAmount.Unlimited, LimitAmount.Unlimited)
            u.reserve(auth(1), Long.MAX_VALUE.won(), unlimited)
            u.reserve(auth(2), 1L.won(), unlimited)
            shouldThrow<ArithmeticException> { u.occupiedMinor }
        }
    }
})

package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 반환 가능 금액 통제(CANCEL-CONTRACT-001 §4.3·T2, INV-C1~C3·C8, review H-1): completed + reserved ≤ authorized(항상),
 * cancellable = max(0, authorized − completed − reserved − forced), excess = max(0, completed + reserved + forced − authorized).
 * Payment당 진행 중 반환 1건(D-CANCEL-003), UNKNOWN 예약 유지, 늦은 취소 성공 FORCED 재기록·초과 표시(D-CANCEL-006). 기대값은 명세 표에서 직접 적는다
 * (Demo: 10,000 KRW 승인). 동시 예약의 직렬화(행 잠금)는 CANCEL-001 실DB 시험의 몫이다.
 */
class ReturnBalanceSpec : BehaviorSpec({

    fun id(n: Int) = CancellationFixture.cancellationId(n)

    fun opened(): ReturnBalance = ReturnBalance.open(AuthorizationFixture.authorized().authorization)

    Given("a balance opened from an authorized payment") {
        Then("it starts from the approved amount with nothing returned (status NONE, revision 0)") {
            val s = AuthorizationFixture.authorized()
            val b = ReturnBalance.open(s.authorization)
            b.paymentId shouldBe s.payment.id
            b.authorizationId shouldBe s.authorization.id
            b.originalTransactionId shouldBe checkNotNull(s.authorization.transaction).id
            b.authorized shouldBe 10_000L.won()
            b.completedMinor shouldBe 0L
            b.reservedMinor shouldBe 0L
            b.forcedMinor shouldBe 0L
            b.cancellableMinor shouldBe 10_000L
            b.excessMinor shouldBe 0L
            b.status shouldBe ReturnStatus.NONE
            b.hasInFlight() shouldBe false
            b.revision shouldBe 0L
        }
        Then("it cannot be opened from an authorization without a confirmed transaction") {
            listOf(AuthorizationFixture.started(), AuthorizationFixture.unknownAfterTimeout(), AuthorizationFixture.declined()).forEach { s ->
                shouldThrow<IllegalArgumentException> { ReturnBalance.open(s.authorization) }
            }
        }
    }

    Given("partial returns (CB-01)") {
        Then("3,000 then 7,000: PARTIALLY_CANCELLED then FULLY_CANCELLED, sequences 1/2 with cumulative 3,000/10,000, revision +1 per change") {
            val b = opened()
            b.reserve(id(1), 3_000L.won())
            b.reservedMinor shouldBe 3_000L
            b.cancellableMinor shouldBe 7_000L
            b.hasInFlight() shouldBe true
            b.consume(id(1))
            b.completedMinor shouldBe 3_000L
            b.reservedMinor shouldBe 0L
            b.status shouldBe ReturnStatus.PARTIALLY_CANCELLED
            b.completionOf(id(1)) shouldBe ReturnCompletion(1, 3_000L)
            b.reserve(id(2), 7_000L.won())
            b.completionOf(id(2)) shouldBe null
            b.consume(id(2))
            b.completedMinor shouldBe 10_000L
            b.cancellableMinor shouldBe 0L
            b.status shouldBe ReturnStatus.FULLY_CANCELLED
            b.completionOf(id(2)) shouldBe ReturnCompletion(2, 10_000L)
            b.revision shouldBe 4L
        }
        Then("consuming twice has no further effect (idempotent, no new revision)") {
            val b = opened()
            b.reserve(id(1), 3_000L.won())
            b.consume(id(1))
            b.consume(id(1))
            b.completedMinor shouldBe 3_000L
            b.revision shouldBe 2L
        }
    }

    Given("the reservation guard (callers must have judged the business rejection first — these are defects)") {
        Then("a second reservation while one is RESERVED is rejected (one in-flight return per payment, D-CANCEL-003)") {
            val b = opened()
            b.reserve(id(1), 1_000L.won())
            shouldThrow<IllegalStateException> { b.reserve(id(2), 1_000L.won()) }
            b.reservations.size shouldBe 1
        }
        Then("more than the cancellable amount, another currency, or a reused cancellation id is rejected and nothing changes") {
            val b = opened()
            b.reserve(id(1), 3_000L.won())
            b.consume(id(1))
            shouldThrow<IllegalArgumentException> { b.reserve(id(2), 7_001L.won()) }
            shouldThrow<IllegalArgumentException> { b.reserve(id(2), 100L.usdCents()) }
            shouldThrow<IllegalArgumentException> { b.reserve(id(1), 1_000L.won()) }
            b.cancellableMinor shouldBe 7_000L
            b.reservations.size shouldBe 1
            b.revision shouldBe 2L
        }
    }

    Given("release on a confirmed no-effect outcome") {
        Then("RESERVED → RELEASED gives the amount back; releasing again is a no-op") {
            val b = opened()
            b.reserve(id(1), 3_000L.won())
            b.release(id(1))
            b.reservationOf(id(1))?.state shouldBe ReturnReservationState.RELEASED
            b.cancellableMinor shouldBe 10_000L
            b.hasInFlight() shouldBe false
            b.release(id(1))
            b.cancellableMinor shouldBe 10_000L
        }
        Then("a consumed return is never released and a released one is never consumed") {
            val b = opened()
            b.reserve(id(1), 3_000L.won())
            b.consume(id(1))
            shouldThrow<IllegalStateException> { b.release(id(1)) }
            b.reserve(id(2), 1_000L.won())
            b.release(id(2))
            shouldThrow<IllegalStateException> { b.consume(id(2)) }
        }
    }

    Given("a late cancel success after the return was released (D-CANCEL-006)") {
        Then("RELEASED → FORCED within the approved amount: kept in its own sum, counted against the cancellable amount, not in flight") {
            val b = opened()
            b.reserve(id(1), 3_000L.won())
            b.release(id(1))
            b.reoccupyAfterLateCancel(id(1))
            b.reservationOf(id(1)) shouldBe ReturnReservation(id(1), 3_000L, ReturnReservationState.FORCED, ReturnExcess.WITHIN)
            b.forcedMinor shouldBe 3_000L
            b.reservedMinor shouldBe 0L
            b.cancellableMinor shouldBe 7_000L
            b.excessMinor shouldBe 0L
            b.hasInFlight() shouldBe false
        }
        Then("CB-07 (review H-1) full cancel declined → new 10,000 completed → late success of the first: FORCED 10,000, excess 10,000, cancellable 0") {
            val c = CancellationFixture.paymentWithForcedReturnAfterLateCancelSuccess()
            val b = c.balance
            b.completedMinor shouldBe 10_000L
            b.reservedMinor shouldBe 0L
            b.forcedMinor shouldBe 10_000L
            b.excessMinor shouldBe 10_000L
            b.cancellableMinor shouldBe 0L
            b.status shouldBe ReturnStatus.FULLY_CANCELLED
            b.reservationOf(CancellationFixture.cancellationId(1))?.excess shouldBe ReturnExcess.EXCEEDED
        }
        Then("re-occupying a RESERVED, CONSUMED or already FORCED return has no effect") {
            val b = opened()
            b.reserve(id(1), 1_000L.won())
            b.reoccupyAfterLateCancel(id(1))
            b.reservationOf(id(1))?.state shouldBe ReturnReservationState.RESERVED
            b.consume(id(1))
            b.reoccupyAfterLateCancel(id(1))
            b.reservationOf(id(1))?.state shouldBe ReturnReservationState.CONSUMED
            b.reserve(id(2), 1_000L.won())
            b.release(id(2))
            b.reoccupyAfterLateCancel(id(2))
            b.reoccupyAfterLateCancel(id(2))
            b.forcedMinor shouldBe 1_000L
        }
        Then("a FORCED return is never consumed nor released here (its exit belongs to RECON-CONTRACT-001, fail-closed until then)") {
            val b = opened()
            b.reserve(id(1), 1_000L.won())
            b.release(id(1))
            b.reoccupyAfterLateCancel(id(1))
            shouldThrow<IllegalStateException> { b.consume(id(1)) }
            shouldThrow<IllegalStateException> { b.release(id(1)) }
        }
        Then("excess is flagged exactly on FORCED, a completion sequence exactly on CONSUMED") {
            shouldThrow<IllegalArgumentException> { ReturnReservation(id(1), 1_000L, ReturnReservationState.FORCED) }
            shouldThrow<IllegalArgumentException> { ReturnReservation(id(1), 1_000L, ReturnReservationState.RESERVED, ReturnExcess.WITHIN) }
            shouldThrow<IllegalArgumentException> { ReturnReservation(id(1), 1_000L, ReturnReservationState.CONSUMED) }
            shouldThrow<IllegalArgumentException> { ReturnReservation(id(1), 1_000L, ReturnReservationState.RELEASED, sequence = 1) }
            shouldThrow<IllegalArgumentException> { ReturnReservation(id(1), 0L, ReturnReservationState.RESERVED) }
        }
    }

    Given("a USD authorization (minor unit = cent, CB-04)") {
        Then("10.50 → 5.25 + 5.25 returns exactly, in whole cents") {
            val b = ReturnBalance.open(CancellationFixture.authorizedUsd().authorization)
            b.authorized shouldBe 1_050L.usdCents()
            b.reserve(id(1), 525L.usdCents())
            b.consume(id(1))
            b.reserve(id(2), 525L.usdCents())
            b.consume(id(2))
            b.status shouldBe ReturnStatus.FULLY_CANCELLED
            b.cancellableMinor shouldBe 0L
        }
    }

    Given("persistence restore (CB-06 — stored state that cannot exist is rejected, not repaired)") {
        val s = AuthorizationFixture.authorized()
        val txId = checkNotNull(s.authorization.transaction).id
        fun restore(vararg r: ReturnReservation, authorized: Money = 10_000L.won(), revision: Long = 10) =
            ReturnBalance.reconstitute(s.payment.id, s.authorization.id, txId, authorized, r.toList(), revision)
        Then("a consistent row restores with the same amounts and revision") {
            val b = restore(
                ReturnReservation(id(1), 3_000L, ReturnReservationState.CONSUMED, sequence = 1),
                ReturnReservation(id(2), 1_000L, ReturnReservationState.RELEASED),
                ReturnReservation(id(3), 2_000L, ReturnReservationState.RESERVED),
            )
            b.completedMinor shouldBe 3_000L
            b.reservedMinor shouldBe 2_000L
            b.cancellableMinor shouldBe 5_000L
            b.revision shouldBe 10L
        }
        Then("completed + reserved over the approved amount, two in-flight returns, duplicate ids, broken sequences or a too-low revision are rejected") {
            shouldThrow<IllegalArgumentException> {
                restore(ReturnReservation(id(1), 9_000L, ReturnReservationState.CONSUMED, sequence = 1), ReturnReservation(id(2), 2_000L, ReturnReservationState.RESERVED))
            }
            shouldThrow<IllegalArgumentException> {
                restore(ReturnReservation(id(1), 1_000L, ReturnReservationState.RESERVED), ReturnReservation(id(2), 1_000L, ReturnReservationState.RESERVED))
            }
            shouldThrow<IllegalArgumentException> {
                restore(ReturnReservation(id(1), 1_000L, ReturnReservationState.CONSUMED, sequence = 1), ReturnReservation(id(1), 1_000L, ReturnReservationState.RELEASED))
            }
            shouldThrow<IllegalArgumentException> {
                restore(ReturnReservation(id(1), 1_000L, ReturnReservationState.CONSUMED, sequence = 2))
            }
            shouldThrow<IllegalArgumentException> {
                restore(ReturnReservation(id(1), 1_000L, ReturnReservationState.CONSUMED, sequence = 1), revision = 0)
            }
        }
        Then("FORCED excess flags must explain the stored excess, and the revision must cover every balance-changing event (review n-2)") {
            // 완료 10,000 + FORCED 3,000인데 FORCED가 WITHIN만 → 저장된 초과(3,000)를 설명하는 EXCEEDED가 없다.
            shouldThrow<IllegalArgumentException> {
                restore(
                    ReturnReservation(id(1), 10_000L, ReturnReservationState.CONSUMED, sequence = 1),
                    ReturnReservation(id(2), 3_000L, ReturnReservationState.FORCED, ReturnExcess.WITHIN),
                )
            }
            // 이벤트 수: RESERVED 1, CONSUMED·RELEASED 2, FORCED 3. CONSUMED 1건(2) + FORCED 1건(3) = 5 > revision 4.
            shouldThrow<IllegalArgumentException> {
                restore(
                    ReturnReservation(id(1), 3_000L, ReturnReservationState.CONSUMED, sequence = 1),
                    ReturnReservation(id(2), 1_000L, ReturnReservationState.FORCED, ReturnExcess.WITHIN),
                    revision = 4,
                )
            }
            restore(
                ReturnReservation(id(1), 3_000L, ReturnReservationState.CONSUMED, sequence = 1),
                ReturnReservation(id(2), 1_000L, ReturnReservationState.FORCED, ReturnExcess.WITHIN),
                revision = 5,
            ).revision shouldBe 5L
        }
        Then("an excess coming only from FORCED restores (H-1: evidence, not an over-return the PG made)") {
            val b = restore(
                ReturnReservation(id(1), 10_000L, ReturnReservationState.CONSUMED, sequence = 1),
                ReturnReservation(id(2), 10_000L, ReturnReservationState.FORCED, ReturnExcess.EXCEEDED),
            )
            b.cancellableMinor shouldBe 0L
            b.excessMinor shouldBe 10_000L
        }
        Then("sums that overflow a Long are a defect (CB-05)") {
            shouldThrow<ArithmeticException> {
                restore(
                    ReturnReservation(id(1), Long.MAX_VALUE, ReturnReservationState.CONSUMED, sequence = 1),
                    ReturnReservation(id(2), 1L, ReturnReservationState.FORCED, ReturnExcess.EXCEEDED),
                    authorized = Money(Long.MAX_VALUE, MoneyFixture.KRW),
                )
            }
        }
    }
})

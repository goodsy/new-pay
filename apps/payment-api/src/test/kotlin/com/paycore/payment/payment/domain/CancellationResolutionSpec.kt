package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.CancellationFixture.applyCancel
import com.paycore.payment.payment.domain.CancellationFixture.cancelObservation
import com.paycore.payment.payment.domain.CancellationFixture.confirmedTerms
import com.paycore.payment.payment.domain.InstitutionOutcome.APPROVED
import com.paycore.payment.payment.domain.InstitutionOutcome.DECLINED
import com.paycore.payment.payment.domain.InstitutionOutcome.NOT_FOUND
import com.paycore.payment.payment.domain.InstitutionOutcome.NO_EFFECT_CONFIRMED
import com.paycore.payment.payment.domain.InstitutionOutcome.PENDING
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * 취소 결과 반영 판정표(CANCEL-CONTRACT-001 §4.6·§11.2 CR 행, T5, D-AUTH-003·D-CANCEL-003·006). 완료 1건 = CANCEL 거래 1건 + 예약 CONSUMED, 기관 확정 거절·
 * 무효과·전송 전 실패만 RELEASED, timeout·NOT_FOUND·PENDING은 UNKNOWN·RESERVED 유지, 상충·늦은 성공은 덮어쓰지 않고 관찰 + CANCEL_RESULT_CONFLICT hold
 * (+ 늦은 성공은 FORCED 재기록), 자동 재취소 없음. 기대값은 명세 표에서 직접 적는다. TX2 원자성·응답 유실은 CANCEL-001 실DB·Simulator 시험의 몫이다.
 */
class CancellationResolutionSpec : BehaviorSpec({

    fun dispatched(amount: Long = 3_000L): CancelScenario =
        CancellationFixture.paymentWithReservedReturn(amount.won()).also { it.cancellation.beginDispatch(it.attemptId) }

    fun CancelScenario.reservation() = balance.reservationOf(cancellation.id)

    fun CancelScenario.held() = auth.control.hasActiveHold(auth.payment.id, IncidentReason.CANCEL_RESULT_CONFLICT)

    fun unknown(amount: Long = 3_000L): CancelScenario = dispatched(amount).also {
        CancellationResolution.markOutcomeUnknown(it.cancellation, it.attemptId, UnknownCause.TIMEOUT) shouldBe CancelResolutionOutcome.BecameUnknown
    }

    Given("a confirmed institution cancel matching the request (CR-01·02)") {
        Then("CR-01 OPEN 3,000 → COMPLETED with one CANCEL transaction, reservation CONSUMED, PARTIALLY_CANCELLED, completion 1/3,000") {
            val c = dispatched()
            val out = applyCancel(c, cancelObservation(c, APPROVED)).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            c.cancellation.status shouldBe CancellationStatus.COMPLETED
            c.cancellation.transaction shouldBe out.transaction
            out.transaction.type shouldBe PaymentTransactionType.CANCEL
            out.transaction.money shouldBe 3_000L.won()
            out.transaction.scope shouldBe CancelScope.PARTIAL
            out.transaction.originalTransactionId shouldBe checkNotNull(c.auth.authorization.transaction).id
            out.transaction.institutionTransactionDate shouldBe LocalDate.of(2026, 10, 16)
            out.transaction.confirmedAt shouldBe CancellationFixture.at.plusSeconds(3)
            c.cancellation.attempt(c.attemptId).state shouldBe AttemptState.RESOLVED
            c.reservation()?.state shouldBe ReturnReservationState.CONSUMED
            out.completion shouldBe ReturnCompletion(1, 3_000L)
            out.authorized shouldBe 10_000L.won()
            out.statusAfter shouldBe ReturnStatus.PARTIALLY_CANCELLED
            c.held() shouldBe false
        }
        Then("CR-02 UNKNOWN 10,000 → COMPLETED FULL, FULLY_CANCELLED (the cancel that reaches the approved amount — D-CANCEL-009 input)") {
            val c = unknown(10_000L)
            val out = applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-1")).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            out.transaction.scope shouldBe CancelScope.FULL
            out.statusAfter shouldBe ReturnStatus.FULLY_CANCELLED
            out.completion shouldBe ReturnCompletion(1, 10_000L)
            c.balance.cancellableMinor shouldBe 0L
        }
        Then("the last PARTIAL that reaches the approved amount also reports FULLY_CANCELLED with sequence 2 and cumulative 10,000 (D-CANCEL-009)") {
            val first = CancellationFixture.partiallyCancelled(3_000L.won())
            val second = CancellationFixture.started(first.auth, first.balance, 7_000L.won(), n = 2).also { it.cancellation.beginDispatch(it.attemptId) }
            val out = applyCancel(second, cancelObservation(second, APPROVED), n = 2).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            out.transaction.scope shouldBe CancelScope.PARTIAL
            out.completion shouldBe ReturnCompletion(2, 10_000L)
            out.statusAfter shouldBe ReturnStatus.FULLY_CANCELLED
        }
        Then("completion never touches the daily limit usage, the payment status or the order's authorized history (D-AUTH-007, K-12)") {
            val c = dispatched(10_000L)
            val usageBefore = c.auth.usage.reservations
            applyCancel(c, cancelObservation(c, APPROVED))
            c.auth.usage.reservations shouldBe usageBefore
            c.auth.payment.status shouldBe PaymentStatus.AUTHORIZED
            c.auth.control.authorizedPaymentId shouldBe c.auth.payment.id
        }
        Then("CR-13 after a full return the order still refuses a new payment: AUTHORIZED_NEW_ORDER_REQUIRED without a hold, INCIDENT_HOLD with an active cancel hold (review n-4)") {
            val c = CancellationFixture.partiallyCancelled(10_000L.won())
            c.balance.status shouldBe ReturnStatus.FULLY_CANCELLED
            c.auth.control.decide(OrderFixture.originalIntent, c.auth.payment) shouldBe
                PaymentSlotDecision.Blocked(BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED, c.auth.payment.id)
            applyCancel(c, cancelObservation(c, DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-contra"), n = 2) shouldBe
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            c.auth.control.decide(OrderFixture.originalIntent, c.auth.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, c.auth.payment.id)
        }
    }

    Given("confirmed no-effect outcomes (CR-03·04·05)") {
        Then("CR-03 DECLINED → FAILED(DECLINE), RELEASED, the amount is cancellable again") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, DECLINED)) shouldBe CancelResolutionOutcome.Failed(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
            c.cancellation.status shouldBe CancellationStatus.FAILED
            c.reservation()?.state shouldBe ReturnReservationState.RELEASED
            c.balance.cancellableMinor shouldBe 10_000L
            c.cancellation.transaction shouldBe null
        }
        Then("CR-04 UNKNOWN + NO_EFFECT_CONFIRMED → FAILED(NO_EFFECT), RELEASED") {
            val c = unknown()
            applyCancel(c, cancelObservation(c, NO_EFFECT_CONFIRMED, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe
                CancelResolutionOutcome.Failed(TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT)
            c.reservation()?.state shouldBe ReturnReservationState.RELEASED
        }
        Then("CR-05 gate refusal / recovery fencing before dispatch → FAILED(PRE_TRANSMISSION), RELEASED; connect failure only after dispatch") {
            listOf(AbandonCause.GATE_REFUSED, AbandonCause.FENCED_BY_RECOVERY).forEach { cause ->
                val c = CancellationFixture.paymentWithReservedReturn(3_000L.won())
                CancellationResolution.abandonBeforeDispatch(c.cancellation, c.balance, c.attemptId, cause) shouldBe
                    CancelResolutionOutcome.Failed(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED)
                c.cancellation.status shouldBe CancellationStatus.FAILED
                c.reservation()?.state shouldBe ReturnReservationState.RELEASED
            }
            val d = dispatched()
            CancellationResolution.abandonBeforeDispatch(d.cancellation, d.balance, d.attemptId, AbandonCause.CONNECT_FAILED) shouldBe
                CancelResolutionOutcome.Failed(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED)
            val u = unknown()
            shouldThrow<InvalidCancellationTransitionException> { CancellationResolution.abandonBeforeDispatch(u.cancellation, u.balance, u.attemptId, AbandonCause.CONNECT_FAILED) }
            u.reservation()?.state shouldBe ReturnReservationState.RESERVED
        }
    }

    Given("unclear outcomes (CR-06·07 — timeout, NOT_FOUND and lease expiry never prove no effect)") {
        Then("CR-06 DISPATCHING + timeout → UNKNOWN, RESERVED kept; marking again is StillUnknown") {
            val c = dispatched()
            CancellationResolution.markOutcomeUnknown(c.cancellation, c.attemptId, UnknownCause.TIMEOUT) shouldBe CancelResolutionOutcome.BecameUnknown
            c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            c.reservation()?.state shouldBe ReturnReservationState.RESERVED
            CancellationResolution.markOutcomeUnknown(c.cancellation, c.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe CancelResolutionOutcome.StillUnknown
        }
        Then("CR-06 OPEN + NOT_FOUND/PENDING from the institution → UNKNOWN") {
            listOf(NOT_FOUND, PENDING).forEach { o ->
                val c = dispatched()
                applyCancel(c, cancelObservation(c, o, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe CancelResolutionOutcome.BecameUnknown
                c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            }
        }
        Then("CR-07 UNKNOWN + NOT_FOUND / PENDING / UNKNOWN stays UNKNOWN with RESERVED; it can never become FAILED this way") {
            listOf(NOT_FOUND, PENDING, InstitutionOutcome.UNKNOWN).forEachIndexed { i, o ->
                val c = unknown()
                applyCancel(c, cancelObservation(c, o, source = ObservationSource.INQUIRY, resultId = "inq-$i")) shouldBe CancelResolutionOutcome.StillUnknown
                c.cancellation.status shouldBe CancellationStatus.UNKNOWN
                c.reservation()?.state shouldBe ReturnReservationState.RESERVED
                c.balance.hasInFlight() shouldBe true
            }
        }
        Then("a PREPARED (never sent) attempt ignores unclear results but treats a definitive one as a contract violation (UNKNOWN + hold)") {
            val p = CancellationFixture.paymentWithReservedReturn(3_000L.won())
            applyCancel(p, cancelObservation(p, NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe CancelResolutionOutcome.NoChange
            p.cancellation.status shouldBe CancellationStatus.OPEN
            applyCancel(p, cancelObservation(p, APPROVED)) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            p.cancellation.status shouldBe CancellationStatus.UNKNOWN
            p.reservation()?.state shouldBe ReturnReservationState.RESERVED
            p.held() shouldBe true
        }
    }

    Given("duplicates (CR-08)") {
        Then("CR-08 the same APPROVED again (another source, same terms) after completion has no effect: one transaction, one completion") {
            val c = dispatched()
            val first = applyCancel(c, cancelObservation(c, APPROVED)).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1")) shouldBe CancelResolutionOutcome.NoChange
            applyCancel(c, cancelObservation(c, NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe CancelResolutionOutcome.NoChange
            c.cancellation.transaction shouldBe first.transaction
            c.balance.completedMinor shouldBe 3_000L
            c.held() shouldBe false
        }
        Then("the same key with the same institution content but other receipt time/hash is a duplicate") {
            val c = dispatched()
            val o = cancelObservation(c, APPROVED)
            applyCancel(c, o)
            applyCancel(c, o.copy(observedAt = o.observedAt.plusSeconds(9), evidenceHash = "sha256:other")) shouldBe CancelResolutionOutcome.NoChange
        }
    }

    Given("conflicting results and late success (CR-09..12, D-CANCEL-006 — nothing overwritten, no automatic re-cancel)") {
        Then("CR-09 same key, different content: first kept, conflict evidence appended, in-flight cancel becomes UNKNOWN, hold opens") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, DECLINED))
            val conflicting = cancelObservation(c, APPROVED)
            applyCancel(c, conflicting) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            c.cancellation.keyConflictingObservations shouldBe listOf(conflicting)
            c.cancellation.status shouldBe CancellationStatus.FAILED
            c.reservation()?.state shouldBe ReturnReservationState.FORCED
            c.held() shouldBe true

            val inFlight = dispatched()
            applyCancel(inFlight, cancelObservation(inFlight, NOT_FOUND, resultId = "r-1"))
            applyCancel(inFlight, cancelObservation(inFlight, DECLINED, resultId = "r-1")) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            inFlight.cancellation.status shouldBe CancellationStatus.UNKNOWN
            inFlight.reservation()?.state shouldBe ReturnReservationState.RESERVED
            inFlight.held() shouldBe true
        }
        Then("CR-10 OPEN + APPROVED 2,000 for a 3,000 request (or another original transaction): UNKNOWN + hold, no transaction, RESERVED 3,000 kept") {
            listOf(confirmedTerms(2_000L.won()), confirmedTerms(3_000L.won()).copy(originalProviderTransactionId = "PTX-OTHER")).forEach { terms ->
                val c = dispatched()
                applyCancel(c, cancelObservation(c, APPROVED, terms = terms)) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
                c.cancellation.status shouldBe CancellationStatus.UNKNOWN
                c.cancellation.transaction shouldBe null
                c.reservation() shouldBe ReturnReservation(c.cancellation.id, 3_000L, ReturnReservationState.RESERVED)
                c.held() shouldBe true
                // 상충 뒤 들어온 일치 성공도 자동 확정하지 않는다(해소는 수동·2인·재대사 — D-CANCEL-006/D-AUTH-002 방식).
                applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-ok")) shouldBe CancelResolutionOutcome.NoChange
                c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            }
        }
        Then("CR-11 FAILED (released) + late APPROVED: state kept, no transaction, reservation FORCED with excess flag, hold on this payment") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, DECLINED))
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            c.cancellation.status shouldBe CancellationStatus.FAILED
            c.cancellation.transaction shouldBe null
            c.reservation() shouldBe ReturnReservation(c.cancellation.id, 3_000L, ReturnReservationState.FORCED, ReturnExcess.WITHIN)
            c.held() shouldBe true
            // 같은 늦은 성공 재수신은 효과 없음, 다른 출처의 같은 늦은 성공도 hold·FORCED를 늘리지 않는다.
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe CancelResolutionOutcome.NoChange
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-1")) shouldBe
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            c.auth.control.activeIncidentHolds.size shouldBe 1
            c.balance.forcedMinor shouldBe 3_000L
        }
        Then("CR-11 a FAILED cancel with a late DECLINED duplicate-like result has no effect") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, DECLINED))
            applyCancel(c, cancelObservation(c, DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1")) shouldBe CancelResolutionOutcome.NoChange
            c.held() shouldBe false
        }
        Then("CR-12 COMPLETED + an APPROVED with another provider transaction id, or a DECLINED: state kept, no second transaction, hold opens") {
            listOf(
                { c: CancelScenario -> cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-1", terms = confirmedTerms(3_000L.won(), n = 9)) },
                { c: CancelScenario -> cancelObservation(c, DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1") },
            ).forEach { contradicting ->
                val c = dispatched()
                val done = applyCancel(c, cancelObservation(c, APPROVED)).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
                applyCancel(c, contradicting(c), n = 2) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
                c.cancellation.status shouldBe CancellationStatus.COMPLETED
                c.cancellation.transaction shouldBe done.transaction
                c.balance.completedMinor shouldBe 3_000L
                c.balance.reservations.size shouldBe 1
                c.held() shouldBe true
            }
        }
        Then("a hold opened by one cancellation does not stop a later operator correction cancel from completing on the same payment") {
            val c = CancellationFixture.lateCancelSuccessAfterDecline(3_000L.won())
            val op = CancellationFixture.started(c.auth, c.balance, 7_000L.won(), n = 2, initiator = CancellationFixture.operator(c.auth, 7_000L.won(), c.balance, n = 2))
            op.cancellation.beginDispatch(op.attemptId)
            applyCancel(op, cancelObservation(op, APPROVED), n = 2).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            op.balance.completedMinor shouldBe 7_000L
            op.balance.forcedMinor shouldBe 3_000L
            op.balance.cancellableMinor shouldBe 0L
        }
    }

    Given("the operator correction cancel of the late-confirmed P1 (FAILED payment, RECON-recorded transaction — D-CANCEL-011, review m-1)") {
        fun p1Cancel(): CancelScenario {
            val (p1, _) = CancellationFixture.lateConfirmedFirstGeneration()
            val op = CancellationFixture.operator(p1, 10_000L.won(), null, n = 3, purpose = OperatorCancelPurpose.IncidentOrReconCorrection("recon-case-double-0001"))
            return CancellationFixture.started(p1, null, 10_000L.won(), n = 3, initiator = op).also { it.cancellation.beginDispatch(it.attemptId) }
        }
        Then("an APPROVED for 2,000 against the 10,000 request keeps the cancel UNKNOWN with RESERVED and opens a CANCEL_RESULT_CONFLICT hold on P1") {
            val c = p1Cancel()
            applyCancel(c, cancelObservation(c, APPROVED, terms = confirmedTerms(2_000L.won())), n = 3) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            c.reservation() shouldBe ReturnReservation(c.cancellation.id, 10_000L, ReturnReservationState.RESERVED)
            c.held() shouldBe true
            c.auth.payment.status shouldBe PaymentStatus.FAILED
        }
        Then("a decline followed by a late success writes FORCED and opens a CANCEL_RESULT_CONFLICT hold on P1, P1 stays FAILED") {
            val c = p1Cancel()
            applyCancel(c, cancelObservation(c, DECLINED), n = 3) shouldBe CancelResolutionOutcome.Failed(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"), n = 3) shouldBe
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT)
            c.reservation()?.state shouldBe ReturnReservationState.FORCED
            c.held() shouldBe true
            c.auth.control.hasActiveHold(c.auth.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION) shouldBe true
            c.auth.payment.status shouldBe PaymentStatus.FAILED
        }
    }

    Given("inputs that do not belong together (defects — nothing changes)") {
        Then("a balance or control of another payment, or a cancellation of another payment, throws before any observation is recorded") {
            val c = dispatched()
            val other = CancellationFixture.authorizedSecondGeneration().second
            val otherBalance = CancellationFixture.started(other, null, 1_000L.won(), n = 5, ).balance
            shouldThrow<IllegalArgumentException> {
                CancellationResolution.applyObservation(c.auth.control, c.auth.payment, c.cancellation, otherBalance, cancelObservation(c, APPROVED),
                    CancellationFixture.cancelTransactionId(), CancellationFixture.at)
            }
            shouldThrow<IllegalArgumentException> {
                CancellationResolution.applyObservation(c.auth.control, other.payment, c.cancellation, c.balance, cancelObservation(c, APPROVED),
                    CancellationFixture.cancelTransactionId(), CancellationFixture.at)
            }
            c.cancellation.observations shouldBe emptyList()
            c.cancellation.status shouldBe CancellationStatus.OPEN
        }
    }
})

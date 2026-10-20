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
        CancellationFixture.paymentWithReservedReturn(amount.won()).also { it.cancellation.beginDispatch(it.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION) }

    fun CancelScenario.reservation() = balance.reservationOf(cancellation.id)

    fun CancelScenario.held() = auth.control.hasActiveHold(auth.payment.id, IncidentReason.CANCEL_RESULT_CONFLICT)

    fun unknown(amount: Long = 3_000L): CancelScenario = dispatched(amount).also {
        CancellationResolution.markOutcomeUnknown(it.cancellation, it.balance, it.attemptId, UnknownCause.TIMEOUT) shouldBe CancelResolutionOutcome.BecameUnknown
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
            val second = CancellationFixture.started(first.auth, first.balance, 7_000L.won(), n = 2).also { it.cancellation.beginDispatch(it.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION) }
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
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
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
            // CANCEL-001 F-11(AUTH-001 AU-25·m-2와 같은 규칙): lease 회복이 먼저 UNKNOWN으로 표시한 뒤 늦게 온 connect 실패 보고는 미송신을 증명하지 못한다 — 효과 없음, 예약 유지.
            val u = unknown()
            CancellationResolution.abandonBeforeDispatch(u.cancellation, u.balance, u.attemptId, AbandonCause.CONNECT_FAILED) shouldBe CancelResolutionOutcome.NoChange
            u.cancellation.status shouldBe CancellationStatus.UNKNOWN
            u.cancellation.failureEvidence shouldBe null
            u.reservation()?.state shouldBe ReturnReservationState.RESERVED
        }
    }

    Given("late abandon and lease-expiry reports racing a settled attempt (CANCEL-001 F-11 — NoChange instead of an exception, nothing settled is changed)") {
        Then("a lease-expiry mark arriving after COMPLETED, after a decline or after a pre-dispatch abandon has no effect") {
            val done = CancellationFixture.completed(CancellationFixture.paymentWithReservedReturn(3_000L.won()))
            CancellationResolution.markOutcomeUnknown(done.cancellation, done.balance, done.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe CancelResolutionOutcome.NoChange
            done.cancellation.status shouldBe CancellationStatus.COMPLETED
            done.cancellation.attempt(done.attemptId).state shouldBe AttemptState.RESOLVED
            val declined = CancellationFixture.declined(CancellationFixture.paymentWithReservedReturn(3_000L.won()))
            CancellationResolution.markOutcomeUnknown(declined.cancellation, declined.balance, declined.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe CancelResolutionOutcome.NoChange
            declined.reservation()?.state shouldBe ReturnReservationState.RELEASED
            val fenced = CancellationFixture.paymentWithReservedReturn(3_000L.won())
            CancellationResolution.abandonBeforeDispatch(fenced.cancellation, fenced.balance, fenced.attemptId, AbandonCause.FENCED_BY_RECOVERY)
            CancellationResolution.markOutcomeUnknown(fenced.cancellation, fenced.balance, fenced.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe CancelResolutionOutcome.NoChange
            fenced.cancellation.attempt(fenced.attemptId).state shouldBe AttemptState.ABANDONED_BEFORE_DISPATCH
        }
        Then("a second abandon of an abandoned attempt and an abandon of a resolved attempt have no effect") {
            val fenced = CancellationFixture.paymentWithReservedReturn(3_000L.won())
            CancellationResolution.abandonBeforeDispatch(fenced.cancellation, fenced.balance, fenced.attemptId, AbandonCause.GATE_REFUSED)
            CancellationResolution.abandonBeforeDispatch(fenced.cancellation, fenced.balance, fenced.attemptId, AbandonCause.FENCED_BY_RECOVERY) shouldBe CancelResolutionOutcome.NoChange
            fenced.cancellation.attempt(fenced.attemptId).abandonCause shouldBe AbandonCause.GATE_REFUSED
            val done = CancellationFixture.completed(CancellationFixture.paymentWithReservedReturn(3_000L.won()))
            CancellationResolution.abandonBeforeDispatch(done.cancellation, done.balance, done.attemptId, AbandonCause.CONNECT_FAILED) shouldBe CancelResolutionOutcome.NoChange
            done.reservation()?.state shouldBe ReturnReservationState.CONSUMED
        }
        Then("defects stay defects: connect failure of a never-sent attempt, fencing of a dispatched attempt, lease expiry of a never-sent attempt") {
            val p = CancellationFixture.paymentWithReservedReturn(3_000L.won())
            shouldThrow<InvalidCancellationTransitionException> { CancellationResolution.abandonBeforeDispatch(p.cancellation, p.balance, p.attemptId, AbandonCause.CONNECT_FAILED) }
            shouldThrow<InvalidCancellationTransitionException> { CancellationResolution.markOutcomeUnknown(p.cancellation, p.balance, p.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) }
            val d = dispatched()
            shouldThrow<InvalidCancellationTransitionException> { CancellationResolution.abandonBeforeDispatch(d.cancellation, d.balance, d.attemptId, AbandonCause.FENCED_BY_RECOVERY) }
            d.cancellation.status shouldBe CancellationStatus.OPEN
            d.reservation()?.state shouldBe ReturnReservationState.RESERVED
        }
    }

    Given("incident identity (D-CANCEL1-002·005 — one event per incident, a new hold only if none is active; the same institution decision from another source is the same incident)") {
        fun incident(seq: Int, opened: Boolean) = CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = seq, holdOpened = opened)

        Then("K13 in-flight: a new definitive result on an in-flight cancel that already has an incident counts (+1) without changing state; the same decision via another source does not") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, APPROVED, terms = confirmedTerms(2_000L.won()), resultId = "dec-a")) shouldBe incident(1, true)
            // 같은 결정(dec-a)이 조회로 다시 온다 — 같은 사건.
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "dec-a", terms = confirmedTerms(2_000L.won()))) shouldBe
                CancelResolutionOutcome.NoChange
            // 새 확정 결과(다른 결정의 거절)는 새 상충 사실 — 사건 2, 상태 그대로(자동 종료 없음 — CC-16).
            applyCancel(c, cancelObservation(c, DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1")) shouldBe incident(2, false)
            // 같은 거절 결정이 조회로 — 같은 사건.
            applyCancel(c, cancelObservation(c, DECLINED, source = ObservationSource.INQUIRY, resultId = "ntf-1")) shouldBe CancelResolutionOutcome.NoChange
            // 확정이 아닌 결과는 사건이 아니다.
            applyCancel(c, cancelObservation(c, NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-nf")) shouldBe CancelResolutionOutcome.NoChange
            // 같은 관찰 키·다른 내용은 새 사건이다.
            applyCancel(c, cancelObservation(c, DECLINED, resultId = "dec-a")) shouldBe incident(3, false)
            c.cancellation.incidentCount shouldBe 3
            c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            c.cancellation.transaction shouldBe null
            c.reservation()?.state shouldBe ReturnReservationState.RESERVED
            c.auth.control.activeIncidentHolds.size shouldBe 1
        }
        Then("K13 COMPLETED: another transaction id is an incident; the same contradicting decision from another source or a reconciliation with the same transaction id is not counted again") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, APPROVED)).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            val other = confirmedTerms(3_000L.won(), n = 9)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.NOTIFICATION, resultId = "dec-9", terms = other), n = 2) shouldBe incident(1, true)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "dec-9", terms = other), n = 2) shouldBe CancelResolutionOutcome.NoChange
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-9", terms = other), n = 2) shouldBe CancelResolutionOutcome.NoChange
            applyCancel(c, cancelObservation(c, DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-x"), n = 2) shouldBe incident(2, false)
            c.cancellation.incidentCount shouldBe 2
            c.cancellation.status shouldBe CancellationStatus.COMPLETED
            c.balance.completedMinor shouldBe 3_000L
        }
        Then("K13 FAILED: the late success is one incident however many sources report it; a late success with another transaction id is a second incident") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, DECLINED))
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe incident(1, true)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.NOTIFICATION, resultId = "inq-late")) shouldBe CancelResolutionOutcome.NoChange
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-1")) shouldBe CancelResolutionOutcome.NoChange
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-2", terms = confirmedTerms(3_000L.won(), n = 8))) shouldBe
                incident(2, false)
            c.cancellation.incidentCount shouldBe 2
            c.cancellation.status shouldBe CancellationStatus.FAILED
            c.balance.forcedMinor shouldBe 3_000L
        }
        // D-CANCEL1-007(mid-review #1 H-1): 같은 결정 = 같은 Attempt·같은 결과·같은 확정 조건 + (같은 결정 ID 또는 같은 PTX). PTX만 같고 조건(금액·원 승인 PTX)이 다르면 새 사건이다(fail-closed).
        Then("H-1 in-flight with an incident: the same PTX with another amount or original PTX is a new incident (+1)") {
            val c = dispatched()
            val first = confirmedTerms(2_000L.won())
            applyCancel(c, cancelObservation(c, APPROVED, terms = first, resultId = "dec-a")) shouldBe incident(1, true)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-p",
                terms = CancellationFixture.samePtxDifferentTerms(first, money = 1_500L.won()))) shouldBe incident(2, false)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-p",
                terms = CancellationFixture.samePtxDifferentTerms(first, originalProviderTransactionId = "PTX-OTHER"))) shouldBe incident(3, false)
            c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            c.reservation()?.state shouldBe ReturnReservationState.RESERVED
        }
        Then("H-1 COMPLETED: a success with the completed PTX but another amount or original PTX is an incident that opens the hold") {
            val c = dispatched()
            val done = applyCancel(c, cancelObservation(c, APPROVED)).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-p",
                terms = CancellationFixture.samePtxDifferentTerms(done.transaction.terms, money = 2_000L.won())), n = 2) shouldBe incident(1, true)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-p",
                terms = CancellationFixture.samePtxDifferentTerms(done.transaction.terms, originalProviderTransactionId = "PTX-OTHER")), n = 2) shouldBe incident(2, false)
            c.cancellation.transaction shouldBe done.transaction
            c.held() shouldBe true
        }
        Then("H-1 FAILED: after the late success, the same PTX with another amount is a second incident; FORCED is not doubled") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, DECLINED))
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe incident(1, true)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-p",
                terms = CancellationFixture.samePtxDifferentTerms(confirmedTerms(3_000L.won()), money = 2_500L.won()))) shouldBe incident(2, false)
            c.balance.forcedMinor shouldBe 3_000L
            c.cancellation.status shouldBe CancellationStatus.FAILED
        }
        Then("H-1 KEY_CONFLICT: the same observation key with the same PTX but other terms is a new incident (CC §4.6)") {
            val c = dispatched()
            val done = applyCancel(c, cancelObservation(c, APPROVED)).shouldBeInstanceOf<CancelResolutionOutcome.Completed>()
            applyCancel(c, cancelObservation(c, APPROVED, terms = CancellationFixture.samePtxDifferentTerms(done.transaction.terms, money = 2_000L.won())), n = 2) shouldBe
                incident(1, true)
            c.cancellation.keyConflictingObservations.size shouldBe 1
            c.held() shouldBe true
        }
        Then("re-applying the same observation is a duplicate and never raises the incident sequence") {
            val c = dispatched()
            val o = cancelObservation(c, APPROVED, terms = confirmedTerms(2_000L.won()))
            applyCancel(c, o)
            applyCancel(c, o) shouldBe CancelResolutionOutcome.NoChange
            c.cancellation.incidentCount shouldBe 1
        }
        Then("another cancellation's incident on a payment that is already held is its own first incident without a new hold") {
            val c = CancellationFixture.lateCancelSuccessAfterDecline(3_000L.won())
            val op = CancellationFixture.started(c.auth, c.balance, 7_000L.won(), n = 2, initiator = CancellationFixture.operator(c.auth, 7_000L.won(), c.balance, n = 2))
            op.cancellation.beginDispatch(op.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION)
            applyCancel(op, cancelObservation(op, APPROVED, terms = confirmedTerms(6_000L.won())), n = 2) shouldBe
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = false)
            c.auth.control.activeIncidentHolds.size shouldBe 1
        }
    }

    Given("unclear outcomes (CR-06·07 — timeout, NOT_FOUND and lease expiry never prove no effect)") {
        Then("CR-06 DISPATCHING + timeout → UNKNOWN, RESERVED kept; marking again is StillUnknown") {
            val c = dispatched()
            CancellationResolution.markOutcomeUnknown(c.cancellation, c.balance, c.attemptId, UnknownCause.TIMEOUT) shouldBe CancelResolutionOutcome.BecameUnknown
            c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            c.reservation()?.state shouldBe ReturnReservationState.RESERVED
            CancellationResolution.markOutcomeUnknown(c.cancellation, c.balance, c.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe CancelResolutionOutcome.StillUnknown
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
            applyCancel(p, cancelObservation(p, APPROVED)) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
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
            applyCancel(c, conflicting) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
            c.cancellation.keyConflictingObservations shouldBe listOf(conflicting)
            c.cancellation.status shouldBe CancellationStatus.FAILED
            c.reservation()?.state shouldBe ReturnReservationState.FORCED
            c.held() shouldBe true

            val inFlight = dispatched()
            applyCancel(inFlight, cancelObservation(inFlight, NOT_FOUND, resultId = "r-1"))
            applyCancel(inFlight, cancelObservation(inFlight, DECLINED, resultId = "r-1")) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
            inFlight.cancellation.status shouldBe CancellationStatus.UNKNOWN
            inFlight.reservation()?.state shouldBe ReturnReservationState.RESERVED
            inFlight.held() shouldBe true
        }
        Then("CR-10 OPEN + APPROVED 2,000 for a 3,000 request (or another original transaction): UNKNOWN + hold, no transaction, RESERVED 3,000 kept") {
            listOf(confirmedTerms(2_000L.won()), confirmedTerms(3_000L.won()).copy(originalProviderTransactionId = "PTX-OTHER")).forEach { terms ->
                val c = dispatched()
                applyCancel(c, cancelObservation(c, APPROVED, terms = terms)) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
                c.cancellation.status shouldBe CancellationStatus.UNKNOWN
                c.cancellation.transaction shouldBe null
                c.reservation() shouldBe ReturnReservation(c.cancellation.id, 3_000L, ReturnReservationState.RESERVED)
                c.held() shouldBe true
                // 상충 뒤 들어온 일치 성공도 자동 확정하지 않는다(해소는 수동·2인·재대사 — D-CANCEL-006/D-AUTH-002 방식). 같은 PTX라도 조건이 달라 새 사건이다(D-CANCEL1-005·007).
                applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-ok")) shouldBe
                    CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 2, holdOpened = false)
                c.cancellation.status shouldBe CancellationStatus.UNKNOWN
                c.cancellation.transaction shouldBe null
                c.reservation() shouldBe ReturnReservation(c.cancellation.id, 3_000L, ReturnReservationState.RESERVED)
            }
        }
        Then("CR-11 FAILED (released) + late APPROVED: state kept, no transaction, reservation FORCED with excess flag, hold on this payment") {
            val c = dispatched()
            applyCancel(c, cancelObservation(c, DECLINED))
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
            c.cancellation.status shouldBe CancellationStatus.FAILED
            c.cancellation.transaction shouldBe null
            c.reservation() shouldBe ReturnReservation(c.cancellation.id, 3_000L, ReturnReservationState.FORCED, ReturnExcess.WITHIN)
            c.held() shouldBe true
            // 같은 늦은 성공 재수신은 효과 없음, 다른 출처의 같은 늦은 성공도 hold·FORCED를 늘리지 않는다.
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe CancelResolutionOutcome.NoChange
            // D-CANCEL1-005: 같은 기관 거래(PTX)의 늦은 성공이 대사로 다시 오면 같은 사건 — 사건 수·hold·FORCED 모두 그대로.
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-1")) shouldBe CancelResolutionOutcome.NoChange
            c.cancellation.incidentCount shouldBe 1
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
                applyCancel(c, contradicting(c), n = 2) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
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
            op.cancellation.beginDispatch(op.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION)
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
            return CancellationFixture.started(p1, null, 10_000L.won(), n = 3, initiator = op).also { it.cancellation.beginDispatch(it.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION) }
        }
        Then("an APPROVED for 2,000 against the 10,000 request keeps the cancel UNKNOWN with RESERVED and opens a CANCEL_RESULT_CONFLICT hold on P1") {
            val c = p1Cancel()
            applyCancel(c, cancelObservation(c, APPROVED, terms = confirmedTerms(2_000L.won())), n = 3) shouldBe CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
            c.cancellation.status shouldBe CancellationStatus.UNKNOWN
            c.reservation() shouldBe ReturnReservation(c.cancellation.id, 10_000L, ReturnReservationState.RESERVED)
            c.held() shouldBe true
            c.auth.payment.status shouldBe PaymentStatus.FAILED
        }
        Then("a decline followed by a late success writes FORCED and opens a CANCEL_RESULT_CONFLICT hold on P1, P1 stays FAILED") {
            val c = p1Cancel()
            applyCancel(c, cancelObservation(c, DECLINED), n = 3) shouldBe CancelResolutionOutcome.Failed(TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE)
            applyCancel(c, cancelObservation(c, APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"), n = 3) shouldBe
                CancelResolutionOutcome.Incident(IncidentReason.CANCEL_RESULT_CONFLICT, incidentSeq = 1, holdOpened = true)
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
        // mid-review #1 n-4: TX2·회복이 따로 읽은 두 Aggregate의 상태가 서로 맞아야 한다 — 진행 중 취소 ⇔ RESERVED, 완료 ⇔ CONSUMED, 실패 ⇔ RELEASED·FORCED.
        Then("n-4 a cancellation whose reservation state does not match its status is rejected before any observation is recorded") {
            val c = dispatched()
            val stale = ReturnBalance.reconstitute(
                c.balance.paymentId, c.balance.authorizationId, c.balance.originalTransactionId, c.balance.authorized,
                listOf(ReturnReservation(c.cancellation.id, 3_000L, ReturnReservationState.RELEASED)), 2,
            )
            shouldThrow<IllegalArgumentException> {
                CancellationResolution.applyObservation(c.auth.control, c.auth.payment, c.cancellation, stale, cancelObservation(c, APPROVED),
                    CancellationFixture.cancelTransactionId(), CancellationFixture.at)
            }
            val p = CancellationFixture.paymentWithReservedReturn(3_000L.won())
            val staleOpen = ReturnBalance.reconstitute(
                p.balance.paymentId, p.balance.authorizationId, p.balance.originalTransactionId, p.balance.authorized,
                listOf(ReturnReservation(p.cancellation.id, 3_000L, ReturnReservationState.RELEASED)), 2,
            )
            shouldThrow<IllegalArgumentException> { CancellationResolution.abandonBeforeDispatch(p.cancellation, staleOpen, p.attemptId, AbandonCause.GATE_REFUSED) }
            // S7(검토 #2 지시 — 검토 #1 n-4의 남은 경로): lease 만료·결과 불명확 표시도 같은 정합 검사를 먼저 한다.
            shouldThrow<IllegalArgumentException> { CancellationResolution.markOutcomeUnknown(c.cancellation, stale, c.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) }
            c.cancellation.attempt(c.attemptId).state shouldBe AttemptState.DISPATCHING
            p.cancellation.status shouldBe CancellationStatus.OPEN
            c.cancellation.observations shouldBe emptyList()
            c.cancellation.status shouldBe CancellationStatus.OPEN
        }
    }
})

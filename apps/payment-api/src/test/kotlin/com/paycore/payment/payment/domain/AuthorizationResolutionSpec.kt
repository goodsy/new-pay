package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.apply
import com.paycore.payment.payment.domain.AuthorizationFixture.approvedTerms
import com.paycore.payment.payment.domain.AuthorizationFixture.observation
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

/**
 * 기관 결과 반영 판정표(명세 §5.6, D-AUTH-002·003·007·008)와 Attempt dispatch 전이(§5.2). 각 행의 기대값(상태·거래·hold·한도)은 명세 표에서
 * 직접 적는다. 이 시험은 domain 규칙 증거이며 TX2 원자성·응답 유실·실제 기관 존속은 AUTH-001(실DB·Simulator) 시험의 몫이다.
 */
class AuthorizationResolutionSpec : BehaviorSpec({

    fun AuthorizationScenario.reservation() = usage.reservationOf(authorization.id)?.state

    Given("Attempt dispatch states (TX1 PREPARED → TXd DISPATCHING → result)") {
        Then("PREPARED → DISPATCHING once; dispatching again is rejected") {
            val s = AuthorizationFixture.started()
            s.authorization.beginDispatch(s.attemptId)
            s.authorization.attempt(s.attemptId).state shouldBe AttemptState.DISPATCHING
            s.authorization.attempt(s.attemptId).mayHaveBeenSent() shouldBe true
            shouldThrow<InvalidAuthorizationTransitionException> { s.authorization.beginDispatch(s.attemptId) }
        }
        Then("gate refusal or recovery fencing abandons only a PREPARED attempt → FAILED(PRE_TRANSMISSION_FAILURE_CONFIRMED), limit released") {
            listOf(AbandonCause.GATE_REFUSED, AbandonCause.FENCED_BY_RECOVERY).forEach { cause ->
                val s = AuthorizationFixture.started()
                AuthorizationResolution.abandonBeforeDispatch(s.payment, s.authorization, s.usage, s.attemptId, cause) shouldBe
                    ResolutionOutcome.Failed(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED)
                s.payment.status shouldBe PaymentStatus.FAILED
                s.payment.hasSafeTermination() shouldBe true
                s.authorization.status shouldBe AuthorizationStatus.FAILED
                s.authorization.attempt(s.attemptId).state shouldBe AttemptState.ABANDONED_BEFORE_DISPATCH
                s.reservation() shouldBe LimitReservationState.RELEASED
            }
        }
        Then("a DISPATCHING attempt can be abandoned only for a proven connect failure; fencing it is rejected and changes nothing") {
            val fenced = AuthorizationFixture.dispatching()
            shouldThrow<InvalidAuthorizationTransitionException> {
                AuthorizationResolution.abandonBeforeDispatch(fenced.payment, fenced.authorization, fenced.usage, fenced.attemptId, AbandonCause.FENCED_BY_RECOVERY)
            }
            fenced.payment.status shouldBe PaymentStatus.AUTHORIZING
            fenced.authorization.status shouldBe AuthorizationStatus.OPEN
            fenced.reservation() shouldBe LimitReservationState.RESERVED
            val connect = AuthorizationFixture.dispatching()
            AuthorizationResolution.abandonBeforeDispatch(connect.payment, connect.authorization, connect.usage, connect.attemptId, AbandonCause.CONNECT_FAILED)
            connect.payment.status shouldBe PaymentStatus.FAILED
        }
        Then("a failure after dispatch (timeout) → UNKNOWN, not FAILED; the limit stays reserved; repeating is idempotent") {
            val s = AuthorizationFixture.dispatching()
            AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.TIMEOUT) shouldBe ResolutionOutcome.BecameUnknown
            s.payment.status shouldBe PaymentStatus.UNKNOWN
            s.authorization.status shouldBe AuthorizationStatus.UNKNOWN
            s.authorization.attempt(s.attemptId).unknownCause shouldBe UnknownCause.TIMEOUT
            s.reservation() shouldBe LimitReservationState.RESERVED
            AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe
                ResolutionOutcome.StillUnknown
        }
        Then("a PREPARED attempt cannot become UNKNOWN (it was provably not sent)") {
            val s = AuthorizationFixture.started()
            shouldThrow<InvalidAuthorizationTransitionException> {
                AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.TIMEOUT)
            }
        }
    }

    Given("an open or unknown authorization receiving institution results") {
        Then("APPROVED matching the request → AUTHORIZED with exactly one AUTHORIZATION transaction, order success history, limit consumed") {
            listOf(AuthorizationFixture.dispatching(), AuthorizationFixture.unknownAfterTimeout()).forEach { s ->
                val outcome = apply(s, observation(APPROVED, source = ObservationSource.INQUIRY)).shouldBeInstanceOf<ResolutionOutcome.Authorized>()
                outcome.transaction.type shouldBe PaymentTransactionType.AUTHORIZATION
                outcome.transaction.terms shouldBe approvedTerms()
                outcome.transaction.id shouldBe AuthorizationFixture.transactionId
                s.authorization.transaction shouldBe outcome.transaction
                s.authorization.status shouldBe AuthorizationStatus.AUTHORIZED
                s.payment.status shouldBe PaymentStatus.AUTHORIZED
                s.control.authorizedPaymentId shouldBe s.payment.id
                s.reservation() shouldBe LimitReservationState.CONSUMED
                s.authorization.attempt(s.attemptId).state shouldBe AttemptState.RESOLVED
            }
        }
        Then("DECLINED → FAILED(INSTITUTION_CONFIRMED_DECLINE) and NO_EFFECT_CONFIRMED → FAILED(INSTITUTION_CONFIRMED_NO_EFFECT); limit released; safe for a new payment") {
            mapOf(DECLINED to TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE, NO_EFFECT_CONFIRMED to TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT)
                .forEach { (outcome, evidence) ->
                    val s = AuthorizationFixture.unknownAfterTimeout()
                    apply(s, observation(outcome, source = ObservationSource.INQUIRY)) shouldBe ResolutionOutcome.Failed(evidence)
                    s.payment.status shouldBe PaymentStatus.FAILED
                    s.payment.terminationEvidence shouldBe listOf(evidence)
                    s.authorization.transaction shouldBe null
                    s.reservation() shouldBe LimitReservationState.RELEASED
                    s.control.decide(OrderFixture.originalIntent, s.payment).shouldBeInstanceOf<PaymentSlotDecision.CreateNewPayment>()
                }
        }
        Then("timeout-like results (PENDING, UNKNOWN, NOT_FOUND) never end it: OPEN → UNKNOWN, UNKNOWN stays UNKNOWN, no new payment allowed") {
            val open = AuthorizationFixture.dispatching()
            apply(open, observation(PENDING)) shouldBe ResolutionOutcome.BecameUnknown
            open.payment.status shouldBe PaymentStatus.UNKNOWN
            val unknown = AuthorizationFixture.unknownAfterTimeout()
            listOf(NOT_FOUND, PENDING, InstitutionOutcome.UNKNOWN).forEachIndexed { i, outcome ->
                apply(unknown, observation(outcome, source = ObservationSource.INQUIRY, resultId = "inq-$i")) shouldBe ResolutionOutcome.StillUnknown
            }
            unknown.payment.status shouldBe PaymentStatus.UNKNOWN
            unknown.reservation() shouldBe LimitReservationState.RESERVED
            unknown.authorization.observations.size shouldBe 3
            unknown.control.decide(OrderFixture.originalIntent, unknown.payment) shouldBe
                PaymentSlotDecision.UseExistingPayment(unknown.payment.id, PaymentStatus.UNKNOWN)
        }
        Then("conflicting approvals (amount, partial, plan, card type) → UNKNOWN + hold CONFLICTING, no transaction, limit kept (D-AUTH-008)") {
            listOf(
                approvedTerms(money = 10_001L.won()),
                approvedTerms(money = 9_000L.won()),
                approvedTerms(plan = PaymentPlan.Installment(3)),
                approvedTerms(cardType = CardType.CHECK),
            ).forEach { terms ->
                val s = AuthorizationFixture.dispatching()
                apply(s, observation(APPROVED, terms = terms)) shouldBe ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
                s.payment.status shouldBe PaymentStatus.UNKNOWN
                s.authorization.status shouldBe AuthorizationStatus.UNKNOWN
                s.authorization.transaction shouldBe null
                s.control.authorizedPaymentId shouldBe null
                s.control.activeIncidentHolds shouldBe listOf(IncidentHold(s.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT))
                s.reservation() shouldBe LimitReservationState.RESERVED
            }
        }
        Then("after a conflict hold, later results are recorded but never auto-confirm (resolution is manual)") {
            val s = AuthorizationFixture.dispatching()
            apply(s, observation(APPROVED, terms = approvedTerms(money = 10_001L.won())))
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe ResolutionOutcome.NoChange
            apply(s, observation(DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1")) shouldBe ResolutionOutcome.NoChange
            s.payment.status shouldBe PaymentStatus.UNKNOWN
            s.authorization.observations.size shouldBe 3
        }
        Then("the same observation key received twice has no second effect") {
            val s = AuthorizationFixture.dispatching()
            apply(s, observation(APPROVED))
            apply(s, observation(APPROVED)) shouldBe ResolutionOutcome.NoChange
            s.authorization.observations.size shouldBe 1
        }
        Then("a definitive result for an attempt recorded as never sent is a contract violation → UNKNOWN + hold") {
            val s = AuthorizationFixture.started()
            apply(s, observation(APPROVED)) shouldBe ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            s.payment.status shouldBe PaymentStatus.UNKNOWN
        }
    }

    Given("an AUTHORIZED authorization and another approval report (review M-1: same approval = all approval identifiers equal)") {
        Then("same terms but a different ProviderTransactionId, approval number or institution date is not the same approval → CONFLICTING hold, no second transaction") {
            listOf(
                approvedTerms().copy(providerTransactionId = "PTX-SIM-0002"),
                approvedTerms().copy(approvalNumber = "A0002"),
                approvedTerms().copy(institutionTransactionDate = java.time.LocalDate.of(2026, 10, 16)),
            ).forEach { terms ->
                val s = AuthorizationFixture.authorized()
                val first = s.authorization.transaction
                apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1", terms = terms),
                    newTransactionId = PaymentTransactionId("demo-ptx-0002")) shouldBe
                    ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
                s.authorization.transaction shouldBe first
                s.payment.status shouldBe PaymentStatus.AUTHORIZED
                s.control.hasActiveHold(s.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT) shouldBe true
            }
        }
        Then("all identifiers and terms equal → the same approval, no effect") {
            val s = AuthorizationFixture.authorized()
            apply(s, observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-1", terms = approvedTerms())) shouldBe
                ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
        }
    }

    Given("the same observation key arriving with different content (review m-1)") {
        Then("pending: recorded as conflicting evidence, UNKNOWN + CONFLICTING hold; the first observation is kept; a repeat of the same conflicting content has no further effect") {
            val s = AuthorizationFixture.dispatching()
            apply(s, observation(PENDING)) shouldBe ResolutionOutcome.BecameUnknown
            val conflicting = observation(DECLINED)
            apply(s, conflicting) shouldBe ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            s.payment.status shouldBe PaymentStatus.UNKNOWN
            s.authorization.observations.map { it.outcome } shouldBe listOf(PENDING)
            s.authorization.keyConflictingObservations shouldBe listOf(conflicting)
            s.reservation() shouldBe LimitReservationState.RESERVED
            apply(s, conflicting) shouldBe ResolutionOutcome.NoChange
            s.authorization.keyConflictingObservations.size shouldBe 1
        }
        Then("authorized or failed: state kept, CONFLICTING hold, no transaction change") {
            val authorized = AuthorizationFixture.authorized()
            apply(authorized, observation(APPROVED, terms = approvedTerms(money = 10_001L.won()))) shouldBe
                ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            authorized.payment.status shouldBe PaymentStatus.AUTHORIZED
            val failed = AuthorizationFixture.declined()
            apply(failed, observation(NO_EFFECT_CONFIRMED)) shouldBe ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            failed.payment.status shouldBe PaymentStatus.FAILED
            failed.control.hasActiveHold(failed.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT) shouldBe true
        }
    }

    Given("the same observation key with the same institution content but different receipt metadata (review round2 M-2)") {
        val later = AuthorizationFixture.startedAt.plusSeconds(60)
        Then("OPEN (attempt not yet dispatched): DUPLICATE, no hold") {
            val s = AuthorizationFixture.started()
            apply(s, observation(NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe ResolutionOutcome.NoChange
            apply(s, observation(NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1", observedAt = later, evidenceHash = "sha256:other")) shouldBe
                ResolutionOutcome.NoChange
            s.authorization.status shouldBe AuthorizationStatus.OPEN
            s.authorization.keyConflictingObservations shouldBe emptyList()
            s.control.isOnIncidentHold() shouldBe false
        }
        Then("UNKNOWN: DUPLICATE, no hold") {
            val s = AuthorizationFixture.unknownAfterTimeout()
            apply(s, observation(NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe ResolutionOutcome.StillUnknown
            apply(s, observation(NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1", observedAt = later)) shouldBe ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
            s.authorization.observations.size shouldBe 1
        }
        Then("AUTHORIZED: DUPLICATE, no hold, one transaction") {
            val s = AuthorizationFixture.authorized()
            apply(s, observation(APPROVED, observedAt = later, evidenceHash = null)) shouldBe ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
            s.authorization.keyConflictingObservations shouldBe emptyList()
        }
        Then("FAILED: DUPLICATE, no hold, limit untouched") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(DECLINED, observedAt = later, evidenceHash = "sha256:other")) shouldBe ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
            s.reservation() shouldBe LimitReservationState.RELEASED
        }
    }

    Given("an approval for the current generation after the previous generation's hold was released as REAL (review round2 M-3)") {
        Then("the approval is recorded (transaction + AUTHORIZED) with a double-approval CONFLICTING hold, no exception; order history keeps gen1") {
            // 1) gen1 확정 거절 → gen2 승인 시작·timeout(UNKNOWN) 2) gen1 늦은 승인 hold 3) REAL 해제 4) gen2 APPROVED.
            val first = AuthorizationFixture.declined()
            val second = PaymentFixture.requested(n = 2, slotGeneration = 2)
            first.control.occupy(second, first.payment)
            val gen2 = AuthorizationFixture.startedOn(OrderPaymentScenario(first.control, second, listOf(first.payment, second)), 2, first.usage)
            gen2.authorization.beginDispatch(gen2.attemptId)
            AuthorizationResolution.markOutcomeUnknown(gen2.payment, gen2.authorization, gen2.attemptId, UnknownCause.TIMEOUT)
            apply(first, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            IncidentHoldSettlement.release(
                first.control,
                IncidentHoldRelease(first.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                    "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                first.authorization, first.usage,
            )
            first.control.authorizedPaymentId shouldBe first.payment.id
            val outcome = apply(gen2, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-gen2", attempt = gen2.attemptId),
                newTransactionId = PaymentTransactionId("demo-ptx-0002")).shouldBeInstanceOf<ResolutionOutcome.Authorized>()
            outcome.incident shouldBe IncidentReason.CONFLICTING_INSTITUTION_RESULT
            gen2.authorization.transaction shouldBe outcome.transaction
            second.status shouldBe PaymentStatus.AUTHORIZED
            first.control.authorizedPaymentId shouldBe first.payment.id
            first.control.activeIncidentHolds shouldBe listOf(IncidentHold(second.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT))
            first.control.decide(OrderFixture.originalIntent, second) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, second.id)
            first.usage.reservationOf(gen2.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
            // 이 hold는 DOUBLE_APPROVAL_CORRECTED로 해제한다(주문 이력 gen1 유지, 새 Payment 금지 유지).
            IncidentHoldSettlement.release(
                first.control,
                IncidentHoldRelease(first.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                    "recon-match-demo-2", HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED, "demo", AuthorizationFixture.startedAt.plusSeconds(900),
                    correctionRef = "demo-cancel-0002"),
                gen2.authorization, gen2.usage,
            )
            first.control.isOnIncidentHold() shouldBe false
            first.control.authorizedPaymentId shouldBe first.payment.id
            first.control.decide(OrderFixture.originalIntent, second) shouldBe
                PaymentSlotDecision.Blocked(BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED, first.payment.id)
        }
    }

    Given("new approval evidence after a NO_EFFECT release (D-AUTH-012, review round2 m-6)") {
        Then("a new hold (occurrence 2) + FORCED re-occupation + Incident, all consistent") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            IncidentHoldSettlement.release(
                s.control,
                IncidentHoldRelease(s.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                    "recon-match-demo-1", HoldReleaseFinding.NO_EFFECT_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                s.authorization, s.usage,
            )
            s.reservation() shouldBe LimitReservationState.RELEASED
            s.control.isOnIncidentHold() shouldBe false
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-late-2")) shouldBe
                ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            s.control.activeIncidentHolds shouldBe listOf(IncidentHold(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2))
            s.reservation() shouldBe LimitReservationState.FORCED
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
        }
        Then("after a REAL release new approval evidence opens a new hold + Incident; the limit is already CONSUMED so nothing is re-occupied (D-AUTH-013)") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            IncidentHoldSettlement.release(
                s.control,
                IncidentHoldRelease(s.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                    "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                s.authorization, s.usage,
            )
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-late-2",
                terms = approvedTerms().copy(providerTransactionId = "PTX-SIM-0009"))) shouldBe
                ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            s.control.activeIncidentHolds shouldBe listOf(IncidentHold(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2))
            s.reservation() shouldBe LimitReservationState.CONSUMED
            s.usage.forcedMinor shouldBe 0L
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
        }
        Then("after a DOUBLE_APPROVAL_CORRECTED release a contradicting result for the authorized payment opens a new CONFLICTING hold (D-AUTH-013)") {
            // 이중 승인 hold(gen2 CONFLICTING)를 DOUBLE로 해제한 뒤 gen2에 다른 PTX 승인이 들어온다.
            val first = AuthorizationFixture.declined()
            val second = PaymentFixture.requested(n = 2, slotGeneration = 2)
            first.control.occupy(second, first.payment)
            val gen2 = AuthorizationFixture.startedOn(OrderPaymentScenario(first.control, second, listOf(first.payment, second)), 2, first.usage)
            gen2.authorization.beginDispatch(gen2.attemptId)
            apply(first, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            IncidentHoldSettlement.release(first.control, IncidentHoldRelease(first.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"),
                OperatorId("demo-operator-b"), "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                first.authorization, first.usage)
            apply(gen2, observation(APPROVED, attempt = gen2.attemptId), newTransactionId = PaymentTransactionId("demo-ptx-0002"))
            IncidentHoldSettlement.release(first.control, IncidentHoldRelease(first.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"),
                OperatorId("demo-operator-b"), "recon-match-demo-2", HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED, "demo", AuthorizationFixture.startedAt.plusSeconds(900),
                correctionRef = "demo-cancel-0002"), gen2.authorization, gen2.usage)
            first.control.isOnIncidentHold() shouldBe false
            apply(gen2, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-gen2-2", attempt = gen2.attemptId,
                terms = approvedTerms().copy(providerTransactionId = "PTX-SIM-0010"))) shouldBe
                ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            first.control.activeIncidentHolds shouldBe listOf(IncidentHold(second.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT, 2))
            first.usage.reservationOf(gen2.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
            gen2.authorization.transaction?.id shouldBe PaymentTransactionId("demo-ptx-0002")
        }
    }

    Given("a FAILED authorization receiving a KEY_CONFLICT approval (review round2 m-7)") {
        Then("CONFLICTING hold and the released daily limit is re-occupied (D-AUTH-010)") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED)) shouldBe ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            s.control.hasActiveHold(s.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT) shouldBe true
            s.reservation() shouldBe LimitReservationState.FORCED
            s.authorization.keyConflictingObservations.single().outcome shouldBe APPROVED
        }
        Then("a KEY_CONFLICT non-approval on FAILED holds but does not re-occupy") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(NO_EFFECT_CONFIRMED)) shouldBe ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            s.reservation() shouldBe LimitReservationState.RELEASED
        }
    }

    Given("an earlier generation on hold while the current generation's approval arrives (D-AUTH-011)") {
        Then("the current approval is recorded as a transaction (PaymentAuthorized) and the order hold stays") {
            // 1번 시도: 승인 시작 → 기관 거절. 2번 시도가 슬롯을 점유하고 승인 시작·전송. 그 뒤 1번에 늦은 승인 → hold. 그 뒤 2번 승인.
            val first = AuthorizationFixture.declined()
            val second = PaymentFixture.requested(n = 2, slotGeneration = 2)
            first.control.occupy(second, first.payment)
            val gen2 = AuthorizationFixture.startedOn(OrderPaymentScenario(first.control, second, listOf(first.payment, second)), 2, first.usage)
            gen2.authorization.beginDispatch(gen2.attemptId)
            apply(first, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe
                ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            val approved = apply(gen2, observation(APPROVED, attempt = gen2.attemptId), newTransactionId = PaymentTransactionId("demo-ptx-0002"))
            approved.shouldBeInstanceOf<ResolutionOutcome.Authorized>().transaction.paymentId shouldBe second.id
            second.status shouldBe PaymentStatus.AUTHORIZED
            first.control.authorizedPaymentId shouldBe second.id
            first.control.activeIncidentHolds shouldBe listOf(IncidentHold(first.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION))
            first.usage.reservationOf(gen2.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
            first.usage.reservationOf(first.authorization.id)?.state shouldBe LimitReservationState.FORCED
        }
    }

    Given("an AUTHORIZED authorization") {
        Then("the same approval from another source has no effect (one financial fact per institution effect, D-AUTH-003)") {
            val s = AuthorizationFixture.authorized()
            val first = s.authorization.transaction
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1")) shouldBe ResolutionOutcome.NoChange
            apply(s, observation(NOT_FOUND, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe ResolutionOutcome.NoChange
            s.authorization.transaction shouldBe first
            s.control.isOnIncidentHold() shouldBe false
        }
        Then("a contradicting result (decline, other amount) keeps AUTHORIZED and opens a CONFLICTING hold; nothing is auto-cancelled") {
            listOf(observation(DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1"),
                observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rec-1", terms = approvedTerms(money = 20_000L.won())))
                .forEach { contradicting ->
                    val s = AuthorizationFixture.authorized()
                    apply(s, contradicting) shouldBe ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
                    s.payment.status shouldBe PaymentStatus.AUTHORIZED
                    s.authorization.status shouldBe AuthorizationStatus.AUTHORIZED
                    s.reservation() shouldBe LimitReservationState.CONSUMED
                    s.control.hasActiveHold(s.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT) shouldBe true
                }
        }
    }

    Given("a FAILED authorization (confirmed decline)") {
        Then("a late approval keeps FAILED, creates no transaction and opens SUCCESS_AFTER_TERMINATION hold (D-AUTH-002·003)") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe
                ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            s.payment.status shouldBe PaymentStatus.FAILED
            s.authorization.transaction shouldBe null
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
            // D-AUTH-010(사용자 답변 "다시 점유", review n-1): 해제했던 점유를 승인 시작일 날짜에 다시 기록한다(FORCED, 초과 표시).
            s.usage.reservationOf(s.authorization.id) shouldBe
                LimitReservation(s.authorization.id, 10_000, LimitReservationState.FORCED, LimitExcess.WITHIN)
            s.usage.forcedMinor shouldBe 10_000L
            s.usage.occupiedMinor shouldBe 10_000L
        }
        Then("the forced re-occupation is recorded even over the daily limit (never refused), marked EXCEEDED, and counts against new authorizations (D-AUTH-010)") {
            val s = AuthorizationFixture.declined()
            val tight = AuthorizationFixture.limitPolicy(daily = LimitAmount.Limited(5_000))
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"), policy = tight)
            s.usage.reservationOf(s.authorization.id)?.excess shouldBe LimitExcess.EXCEEDED
            // 기관에 실제 승인이 있을 수 있는 동안 한도에 반영한다: FORCED 10,000원이 일 5,000원 한도를 이미 넘어 1원도 들어가지 않는다.
            s.usage.occupiedMinor shouldBe 10_000L
            s.usage.rejectionFor(1L.won(), tight) shouldBe LimitRejection.DAILY_LIMIT_EXCEEDED
            // 해제 판정 NO_EFFECT → RELEASED → 합에서 빠진다.
            s.usage.settleForced(s.authorization.id, HoldReleaseFinding.NO_EFFECT_CONFIRMED)
            s.usage.rejectionFor(5_000L.won(), tight) shouldBe null
        }
        Then("without a known policy the forced re-occupation is still recorded with UNDETERMINED excess; repeated late approvals do not double it") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"), policy = null)
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-late"), policy = null) shouldBe
                ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            s.usage.reservationOf(s.authorization.id)?.excess shouldBe LimitExcess.UNDETERMINED
            s.usage.forcedMinor shouldBe 10_000L
            s.control.incidentHolds.size shouldBe 1
        }
        Then("a duplicate decline has no effect") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(DECLINED, source = ObservationSource.INQUIRY, resultId = "inq-1")) shouldBe ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
        }
    }

    Given("the single completion operation on the control (§17-5)") {
        Then("it marks the payment AUTHORIZED and records order success history together; a REQUESTED or stale payment changes nothing") {
            val order = OrderPaymentControlFixture.openedWithRequestedPayment()
            shouldThrow<InvalidPaymentTransitionException> { order.control.completeAuthorization(order.current) }
            order.current.status shouldBe PaymentStatus.REQUESTED
            order.control.authorizedPaymentId shouldBe null
            val retried = OrderPaymentControlFixture.retriedAfterConfirmedDecline()
            shouldThrow<StaleSlotException> { retried.control.completeAuthorization(retried.history.first()) }
            val s = AuthorizationFixture.unknownAfterTimeout()
            s.control.completeAuthorization(s.payment)
            s.payment.status shouldBe PaymentStatus.AUTHORIZED
            s.control.authorizedPaymentId shouldBe s.payment.id
        }
    }

    Given("inputs that do not belong together") {
        Then("they are rejected before any observation is recorded") {
            val s = AuthorizationFixture.dispatching()
            val other = AuthorizationFixture.dispatching()
            shouldThrow<IllegalArgumentException> {
                AuthorizationResolution.applyObservation(s.control, PaymentFixture.requested(n = 2), s.authorization, s.usage, observation(APPROVED), AuthorizationFixture.transactionId, null, AuthorizationFixture.startedAt)
            }
            shouldThrow<IllegalArgumentException> {
                AuthorizationResolution.applyObservation(s.control, s.payment, s.authorization, DailyLimitUsage.open(AuthorizationFixture.usageKey(AuthorizationFixture.startedAt.plusSeconds(86_400))), observation(APPROVED), AuthorizationFixture.transactionId, null, AuthorizationFixture.startedAt)
            }
            shouldThrow<IllegalArgumentException> { apply(s, observation(APPROVED, attempt = AttemptId("demo-att-9999"))) }
            s.authorization.observations shouldBe emptyList()
            other.authorization.observations shouldBe emptyList()
        }
        Then("an approval observation needs approved terms and others must not carry them") {
            shouldThrow<IllegalArgumentException> { observation(APPROVED, terms = null) }
            shouldThrow<IllegalArgumentException> { observation(DECLINED, terms = approvedTerms()) }
        }
    }
})

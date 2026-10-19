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
            s.authorization.beginDispatch(s.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
            s.authorization.attempt(s.attemptId).state shouldBe AttemptState.DISPATCHING
            s.authorization.attempt(s.attemptId).mayHaveBeenSent() shouldBe true
            shouldThrow<InvalidAuthorizationTransitionException> { s.authorization.beginDispatch(s.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION) }
        }
        // D-AUTH1-007(Q-S8-1 = A): 전송 게이트 통과 때 Route 프로파일(Capability) 버전을 Attempt에 고정한다 — 회복의 무효과 종결 요청 근거.
        Then("TXd fixes the route profile version on the attempt; a PREPARED attempt has none; a blank version is refused without changing the attempt (D-AUTH1-007)") {
            val s = AuthorizationFixture.started()
            s.authorization.attempt(s.attemptId).routeProfileVersion shouldBe null
            shouldThrow<IllegalArgumentException> { s.authorization.beginDispatch(s.attemptId, " ") }
            s.authorization.attempt(s.attemptId).state shouldBe AttemptState.PREPARED
            s.authorization.beginDispatch(s.attemptId, "sim-van-a-demo-1")
            s.authorization.attempt(s.attemptId).routeProfileVersion shouldBe "sim-van-a-demo-1"
        }
        Then("gate refusal or recovery fencing abandons only a PREPARED attempt → FAILED(PRE_TRANSMISSION_FAILURE_CONFIRMED), limit released") {
            listOf(AbandonCause.GATE_REFUSED, AbandonCause.FENCED_BY_RECOVERY).forEach { cause ->
                val s = AuthorizationFixture.started()
                AuthorizationResolution.abandonBeforeDispatch(s.payment, s.authorization, s.usage, s.attemptId, cause, AuthorizationFixture.startedAt.plusSeconds(1)) shouldBe
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
                AuthorizationResolution.abandonBeforeDispatch(fenced.payment, fenced.authorization, fenced.usage, fenced.attemptId, AbandonCause.FENCED_BY_RECOVERY, AuthorizationFixture.startedAt.plusSeconds(1))
            }
            fenced.payment.status shouldBe PaymentStatus.AUTHORIZING
            fenced.authorization.status shouldBe AuthorizationStatus.OPEN
            fenced.reservation() shouldBe LimitReservationState.RESERVED
            val connect = AuthorizationFixture.dispatching()
            AuthorizationResolution.abandonBeforeDispatch(connect.payment, connect.authorization, connect.usage, connect.attemptId, AbandonCause.CONNECT_FAILED, AuthorizationFixture.startedAt.plusSeconds(1))
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
        // AUTH-001 rev 0.3 m-2: 연결 실패 보고가 lease 회복(OUTCOME_UNKNOWN)·결과 확정보다 늦게 도착하면 전송 전 실패를 증명할 수 없다 → 효과 없음, UNKNOWN 유지.
        Then("a connect failure reported after recovery already marked the attempt OUTCOME_UNKNOWN is NoChange: it stays UNKNOWN (m-2)") {
            val s = AuthorizationFixture.dispatching()
            AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED)
            AuthorizationResolution.abandonBeforeDispatch(s.payment, s.authorization, s.usage, s.attemptId, AbandonCause.CONNECT_FAILED, AuthorizationFixture.startedAt.plusSeconds(1)) shouldBe ResolutionOutcome.NoChange
            s.payment.status shouldBe PaymentStatus.UNKNOWN
            s.authorization.attempt(s.attemptId).state shouldBe AttemptState.OUTCOME_UNKNOWN
            s.reservation() shouldBe LimitReservationState.RESERVED
        }
        Then("a connect failure after the result was confirmed, or a second fencing of an abandoned attempt, is NoChange (m-2, n-6)") {
            val authorized = AuthorizationFixture.authorized()
            AuthorizationResolution.abandonBeforeDispatch(authorized.payment, authorized.authorization, authorized.usage, authorized.attemptId, AbandonCause.CONNECT_FAILED, AuthorizationFixture.startedAt.plusSeconds(1)) shouldBe
                ResolutionOutcome.NoChange
            authorized.payment.status shouldBe PaymentStatus.AUTHORIZED
            val fenced = AuthorizationFixture.started()
            AuthorizationResolution.abandonBeforeDispatch(fenced.payment, fenced.authorization, fenced.usage, fenced.attemptId, AbandonCause.FENCED_BY_RECOVERY, AuthorizationFixture.startedAt.plusSeconds(1))
            AuthorizationResolution.abandonBeforeDispatch(fenced.payment, fenced.authorization, fenced.usage, fenced.attemptId, AbandonCause.FENCED_BY_RECOVERY, AuthorizationFixture.startedAt.plusSeconds(1)) shouldBe
                ResolutionOutcome.NoChange
            fenced.payment.terminationEvidence shouldBe listOf(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED)
        }
        Then("a connect failure for a PREPARED attempt is a defect (it was never dispatched): rejected") {
            val s = AuthorizationFixture.started()
            shouldThrow<InvalidAuthorizationTransitionException> {
                AuthorizationResolution.abandonBeforeDispatch(s.payment, s.authorization, s.usage, s.attemptId, AbandonCause.CONNECT_FAILED, AuthorizationFixture.startedAt.plusSeconds(1))
            }
            s.payment.status shouldBe PaymentStatus.AUTHORIZING
        }
    }

    // AUTH-001 rev 0.3 m-6: 종료 근거는 출처 기록(종류·참조·기록 시각·기록자)과 함께 남는다(Auditability). 출처가 관찰이면 관찰 키, 전송 전 포기면 Attempt.
    Given("termination evidence recorded by the resolution carries its source (m-6)") {
        Then("a confirmed decline records the observation as the source") {
            val s = AuthorizationFixture.declined()
            val record = s.payment.terminationEvidenceRecords.single()
            record.evidence shouldBe TerminationEvidence.INSTITUTION_CONFIRMED_DECLINE
            record.source shouldBe TerminationEvidenceSource(
                TerminationEvidenceSourceKind.OBSERVATION, "demo-att-0001/RESPONSE/sim-result-1", AuthorizationFixture.startedAt.plusSeconds(3), TerminationEvidenceSource.SYSTEM,
            )
        }
        Then("an abandoned attempt records the attempt as the source") {
            val s = AuthorizationFixture.started()
            AuthorizationResolution.abandonBeforeDispatch(s.payment, s.authorization, s.usage, s.attemptId, AbandonCause.GATE_REFUSED, AuthorizationFixture.startedAt.plusSeconds(1))
            s.payment.terminationEvidenceRecords.single().source shouldBe TerminationEvidenceSource(
                TerminationEvidenceSourceKind.ATTEMPT_ABANDON, "demo-att-0001", AuthorizationFixture.startedAt.plusSeconds(1), TerminationEvidenceSource.SYSTEM,
            )
        }
    }

    // AUTH-001 rev 0.3 H-1: 해제 판정은 그 hold를 연 관찰만 설명한다. hold가 활성인 동안 흡수된 다른 승인(새 hold·사건 없음, AU-17)은 설명되지 않는다.
    // rev 0.5 D-AUTH1-005(m-D): 그 hold는 재보고를 기다리지 않고 **해제 연산에서** 흡수된 관찰을 출처로 열린다. 이후 재보고는 그 활성 hold에 흡수된다.
    Given("an approval absorbed while a hold was active, re-reported after that hold was released as REAL (H-1)") {
        Then("the release opens a new hold from the absorbed approval (D-AUTH1-005); the re-report is absorbed by it") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            val hold = s.control.activeIncidentHolds.single()
            hold.sourceObservation shouldBe ObservationRef(ObservationKey(s.attemptId, ObservationSource.INQUIRY, "inq-late"), 0)
            // 다른 승인 C(다른 PTX)가 hold 활성 중에 온다 — 새 hold 없음(같은 활성 hold).
            val other = approvedTerms().copy(providerTransactionId = "PTX-SIM-0042", approvalNumber = "A0042")
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-c", terms = other))
            s.control.incidentHolds.size shouldBe 1
            IncidentHoldSettlement.release(s.control, IncidentHoldRelease(hold, OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)), s.authorization, s.usage)
            s.control.activeIncidentHolds.single().occurrence shouldBe 2
            s.control.activeIncidentHolds.single().sourceObservation shouldBe ObservationRef(ObservationKey(s.attemptId, ObservationSource.NOTIFICATION, "ntf-c"), 0)
            apply(s, observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rcn-c", terms = other)) shouldBe
                ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            s.control.incidentHolds.size shouldBe 2
            s.control.activeIncidentHolds.single().sourceObservation shouldBe ObservationRef(ObservationKey(s.attemptId, ObservationSource.NOTIFICATION, "ntf-c"), 0)
        }
    }

    // AUTH-001 검토 #1 H-1(미완 — 상충 variant): 같은 관찰 키 K의 variant 0(조건 A)이 hold를 열고 variant 1(조건 B)이 활성 hold에 흡수됐다. REAL 해제는 A만 설명한다 —
    // B가 다른 키로 재보고되면 새 사건이다(키만 비교하면 K가 설명 집합에 있어 B가 검사되지 않는다).
    Given("a key-conflict variant absorbed while the hold opened by variant 0 was active, re-reported under a new key after a REAL release (review #1 H-1)") {
        Then("the release opens a new hold from the absorbed variant 1 (D-AUTH1-005); a re-report of B is absorbed, a re-report of A stays explained") {
            val s = AuthorizationFixture.authorized()
            val termsA = approvedTerms().copy(providerTransactionId = "PTX-SIM-00A1", approvalNumber = "A00A1")
            val termsB = approvedTerms().copy(providerTransactionId = "PTX-SIM-00B2", approvalNumber = "A00B2")
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-k", terms = termsA)) shouldBe
                ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            val hold = s.control.activeIncidentHolds.single()
            hold.sourceObservation shouldBe ObservationRef(ObservationKey(s.attemptId, ObservationSource.INQUIRY, "inq-k"), 0)
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-k", terms = termsB))
            s.authorization.refOf(s.authorization.keyConflictingObservations.single()) shouldBe ObservationRef(ObservationKey(s.attemptId, ObservationSource.INQUIRY, "inq-k"), 1)
            s.authorization.keyConflictingObservations.single().approved shouldBe termsB
            s.control.incidentHolds.size shouldBe 1
            IncidentHoldSettlement.release(s.control, IncidentHoldRelease(hold, OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)), s.authorization, s.usage)
            s.control.activeIncidentHolds.single().occurrence shouldBe 2
            s.control.activeIncidentHolds.single().sourceObservation shouldBe ObservationRef(ObservationKey(s.attemptId, ObservationSource.INQUIRY, "inq-k"), 1)
            apply(s, observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rcn-b", terms = termsB)) shouldBe
                ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            s.control.activeIncidentHolds.single().occurrence shouldBe 2
            // A 자체의 재보고(다른 키)는 해제 판정이 설명한 승인이다.
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-a", terms = termsA))
            s.control.incidentHolds.size shouldBe 2
        }
    }

    // 검토 #2 m-C: 기관이 무효과 종결을 확정한 뒤 늦게 도착한 PENDING(종결과 동시에 만들어진 예약)은 종료 근거도 승인도 아니다 — 아무것도 바꾸지 않는다.
    Given("a PENDING observation after FAILED(NO_EFFECT) (review #2 m-C)") {
        Then("it is NoChange: no hold, the payment stays FAILED with its evidence") {
            val s = AuthorizationFixture.unknownAfterTimeout()
            apply(s, observation(NO_EFFECT_CONFIRMED, source = ObservationSource.INQUIRY, resultId = "vc-closure")) shouldBe ResolutionOutcome.Failed(TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT)
            apply(s, observation(PENDING, source = ObservationSource.RESPONSE, resultId = "vp-pending")) shouldBe ResolutionOutcome.NoChange
            s.control.incidentHolds shouldBe emptyList()
            s.payment.status shouldBe PaymentStatus.FAILED
            s.authorization.status shouldBe AuthorizationStatus.FAILED
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
                s.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(s.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT, 1, null))
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
                // 검토 #1 m-2: 기관 영업일도 기관 승인 조건이다(값의 시간대·출처 의미는 Q-CALENDAR — 여기서는 같음 비교만).
                approvedTerms().copy(institutionBusinessDate = java.time.LocalDate.of(2026, 10, 16)),
                approvedTerms().copy(institutionBusinessDate = null),
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
            gen2.authorization.beginDispatch(gen2.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
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
            first.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(second.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT, 1, null))
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
            s.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null))
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
            s.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null))
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
            gen2.authorization.beginDispatch(gen2.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
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
            first.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(second.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT, 2, null))
            first.usage.reservationOf(gen2.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
            gen2.authorization.transaction?.id shouldBe PaymentTransactionId("demo-ptx-0002")
        }
        // AUTH-001 m-10(D-AUTH-013 "다른 기관 거래 식별자" 문구): REAL·DOUBLE 해제 판정은 그때 확인한 승인을 설명한다 — 같은 승인(terms 전체 일치)의 재보고는 새 사건이 아니다.
        Then("after a REAL release a re-report of the same approval (same terms, new observation key) is NoChange: no new hold, limit stays CONSUMED (m-10)") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            IncidentHoldSettlement.release(
                s.control,
                IncidentHoldRelease(s.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                    "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                s.authorization, s.usage,
            )
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-late-2")) shouldBe ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
            s.control.incidentHolds.size shouldBe 1
            s.reservation() shouldBe LimitReservationState.CONSUMED
            s.authorization.observations.size shouldBe 3
        }
        Then("a REAL release (approval X) then a NO_EFFECT release (approval Y): Y again opens a new hold (D-AUTH-012), X re-reported stays explained (H-1)") {
            val s = AuthorizationFixture.declined()
            apply(s, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            IncidentHoldSettlement.release(s.control, IncidentHoldRelease(s.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"),
                OperatorId("demo-operator-b"), "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                s.authorization, s.usage)
            apply(s, observation(APPROVED, source = ObservationSource.NOTIFICATION, resultId = "ntf-other",
                terms = approvedTerms().copy(providerTransactionId = "PTX-SIM-0009"))) shouldBe ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            IncidentHoldSettlement.release(s.control, IncidentHoldRelease(s.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"),
                OperatorId("demo-operator-b"), "recon-match-demo-2", HoldReleaseFinding.NO_EFFECT_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(900)),
                s.authorization, s.usage)
            // X(첫 hold를 연 승인)는 REAL 판정이 설명한다 → 효과 없음.
            apply(s, observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rcn-1")) shouldBe ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
            // Y(NO_EFFECT로 해제된 hold를 연 승인)의 재보고는 판정과 모순 → 새 hold(occurrence 3).
            apply(s, observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rcn-2",
                terms = approvedTerms().copy(providerTransactionId = "PTX-SIM-0009"))) shouldBe ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            s.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 3, null))
        }
        // CANCEL-CONTRACT-001 §14.1 nit 3: RECON이 거래를 기록한 FAILED 승인(D-CANCEL-011·013)에 그 거래와 같은 승인이 새 관찰 키로 오면 효과 없음.
        Then("a FAILED authorization with a transaction recorded by reconciliation and a REAL release: the same approval re-reported is NoChange (nit 3)") {
            val s = AuthorizationFixture.declined()
            val late = observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")
            apply(s, late)
            s.authorization.recordLateApprovalByReconciliation(
                AuthorizationTransaction(PaymentTransactionId("demo-ptx-late"), s.authorization.id, s.payment.id, late.approved!!, late.key,
                    AuthorizationFixture.startedAt.plusSeconds(300)),
            )
            IncidentHoldSettlement.release(s.control, IncidentHoldRelease(s.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"),
                OperatorId("demo-operator-b"), "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                s.authorization, s.usage)
            apply(s, observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rcn-2")) shouldBe ResolutionOutcome.NoChange
            s.control.isOnIncidentHold() shouldBe false
            apply(s, observation(APPROVED, source = ObservationSource.RECONCILIATION, resultId = "rcn-3",
                terms = approvedTerms().copy(approvalNumber = "A0099"))) shouldBe ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
        }
    }

    // AUTH-001 n-3(명세 §4.11-3): 회복 Worker의 lease 만료 표시와 TX2 결과 반영이 경합해 늦게 도착한 표시는 예외가 아니라 효과 없음이다.
    Given("marking the outcome unknown after the attempt was already resolved or abandoned (n-3)") {
        Then("an AUTHORIZED authorization (attempt RESOLVED): NoChange, nothing changes") {
            val s = AuthorizationFixture.authorized()
            AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe ResolutionOutcome.NoChange
            s.authorization.status shouldBe AuthorizationStatus.AUTHORIZED
            s.payment.status shouldBe PaymentStatus.AUTHORIZED
        }
        Then("a FAILED authorization after a confirmed decline: NoChange") {
            val s = AuthorizationFixture.declined()
            AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe ResolutionOutcome.NoChange
            s.authorization.status shouldBe AuthorizationStatus.FAILED
        }
        Then("an attempt abandoned before dispatch (recovery fencing won): NoChange") {
            val s = AuthorizationFixture.started()
            AuthorizationResolution.abandonBeforeDispatch(s.payment, s.authorization, s.usage, s.attemptId, AbandonCause.FENCED_BY_RECOVERY, AuthorizationFixture.startedAt.plusSeconds(1))
            AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED) shouldBe ResolutionOutcome.NoChange
            s.authorization.attempt(s.attemptId).state shouldBe AttemptState.ABANDONED_BEFORE_DISPATCH
        }
        Then("a PREPARED attempt is still a defect (it was never sent): rejected") {
            val s = AuthorizationFixture.started()
            shouldThrow<InvalidAuthorizationTransitionException> {
                AuthorizationResolution.markOutcomeUnknown(s.payment, s.authorization, s.attemptId, UnknownCause.DISPATCH_LEASE_EXPIRED)
            }
            s.authorization.status shouldBe AuthorizationStatus.OPEN
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
            gen2.authorization.beginDispatch(gen2.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
            apply(first, observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late")) shouldBe
                ResolutionOutcome.Incident(IncidentReason.SUCCESS_AFTER_TERMINATION)
            val approved = apply(gen2, observation(APPROVED, attempt = gen2.attemptId), newTransactionId = PaymentTransactionId("demo-ptx-0002"))
            approved.shouldBeInstanceOf<ResolutionOutcome.Authorized>().transaction.paymentId shouldBe second.id
            second.status shouldBe PaymentStatus.AUTHORIZED
            first.control.authorizedPaymentId shouldBe second.id
            first.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(first.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null))
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

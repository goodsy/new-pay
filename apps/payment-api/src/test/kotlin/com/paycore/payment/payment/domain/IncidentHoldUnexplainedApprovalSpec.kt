package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.apply
import com.paycore.payment.payment.domain.AuthorizationFixture.approvedTerms
import com.paycore.payment.payment.domain.AuthorizationFixture.observation
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * D-AUTH1-005(Q-AUTH1-05, 검토 #2 m-D — 사용자 답변 P-1): CONFLICTING·SUCCESS_AFTER_TERMINATION hold를 REAL·DOUBLE로 해제할 때, 그 hold가 활성인 동안 **흡수한 승인 관찰** 중
 * 해제가 설명하지 못하는 것이 남으면 같은 TX(같은 domain 연산)에서 occurrence + 1 hold를 연다(fail-closed — 해제는 성공, 새 hold의 출처 = 설명되지 않은 관찰의 (key, variant)).
 * "설명" 기준은 [AuthorizationResolution]의 재보고 판정과 같다: 확정 거래와 승인 조건 전체가 같거나, REAL·DOUBLE로 해제된 hold를 연 관찰과 승인 조건 전체가 같다.
 */
class IncidentHoldUnexplainedApprovalSpec : BehaviorSpec({

    val requester = OperatorId("demo-operator-a")
    val approver = OperatorId("demo-operator-b")

    fun release(hold: IncidentHold, finding: HoldReleaseFinding) =
        IncidentHoldRelease(
            hold, requester, approver, "recon-match-demo-1", finding, "demo: re-reconciled", startedAt.plusSeconds(3_600),
            correctionRef = if (finding == HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED) "demo-cancel-0001" else null,
        )

    /** 확정 거절 → 늦은 승인 A(hold 1 출처) → 다른 승인 B(같은 활성 hold에 흡수 — 다른 PTX·승인번호). */
    fun absorbedSecondApproval(bTerms: ApprovedTerms = approvedTerms().copy(approvalNumber = "A0002", providerTransactionId = "PTX-SIM-0002")): AuthorizationScenario =
        AuthorizationFixture.declined().also {
            apply(it, observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late-a"))
            apply(it, observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late-b", terms = bTerms))
            it.control.incidentHolds.size shouldBe 1
        }

    fun refOf(s: AuthorizationScenario, resultId: String): ObservationRef = s.authorization.observationRefs.single { (o, _) -> o.key.sourceResultId == resultId }.second

    Given("B absorbed under an active SUCCESS_AFTER_TERMINATION hold opened by A") {
        Then("releasing the hold as REAL on A succeeds and opens hold occurrence 2 sourced from B in the same operation; the order stays blocked and the forced limit stays FORCED") {
            val s = absorbedSecondApproval()
            val first = s.control.activeIncidentHolds.single()
            first.sourceObservation shouldBe refOf(s, "inq-late-a")
            IncidentHoldSettlement.release(s.control, release(first, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), s.authorization, s.usage)
            s.control.incidentHoldReleases.single().hold shouldBe first
            s.control.authorizedPaymentId shouldBe s.payment.id
            val reopened = s.control.activeIncidentHolds.single()
            reopened shouldBe IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null)
            reopened.sourceObservation shouldBe refOf(s, "inq-late-b")
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
            // 활성 hold ⇔ FORCED(review round2 m-6): 새 hold가 열려 있으므로 점유를 정리하지 않는다.
            s.usage.reservationOf(s.authorization.id)?.state shouldBe LimitReservationState.FORCED
        }
        Then("releasing the reopened hold as REAL explains B too: no further hold, the forced limit is CONSUMED") {
            val s = absorbedSecondApproval()
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), s.authorization, s.usage)
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), s.authorization, s.usage)
            s.control.isOnIncidentHold() shouldBe false
            s.control.incidentHolds.size shouldBe 2
            s.usage.reservationOf(s.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
        }
        Then("a restart restores the reopened hold: reconstitute from the stored holds and release keeps occurrence 2 active with its source") {
            val s = absorbedSecondApproval()
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), s.authorization, s.usage)
            val c = s.control
            val restored = OrderPaymentControl.reconstitute(
                c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, c.authorizedPaymentId,
                c.incidentHolds.map { IncidentHold.reconstitute(it.paymentId, it.reason, it.occurrence, it.sourceObservation) },
                c.incidentHoldReleases,
            )
            val active = restored.activeIncidentHolds.single()
            active.occurrence shouldBe 2
            active.sourceObservation shouldBe refOf(s, "inq-late-b")
            restored.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
        }
    }

    // D-AUTH1-006(Q-AUTH1-06): 해제 판정과 무관하게 같은 규칙 — NO_EFFECT 해제도 흡수된 승인 중 설명되지 않은 것이 남으면 같은 연산에서 occurrence + 1.
    // NO_EFFECT 판정은 어떤 승인도 설명하지 않는다(설명 집합은 확정 거래 terms ∪ REAL·DOUBLE 해제 hold 출처 terms뿐 — D-AUTH-012).
    Given("a NO_EFFECT release of a hold that absorbed approvals (D-AUTH1-006)") {
        Then("B absorbed: the NO_EFFECT release succeeds and opens hold occurrence 2 sourced from B; the order stays blocked and the forced limit stays FORCED") {
            val s = absorbedSecondApproval()
            val first = s.control.activeIncidentHolds.single()
            IncidentHoldSettlement.release(s.control, release(first, HoldReleaseFinding.NO_EFFECT_CONFIRMED), s.authorization, s.usage)
            s.control.incidentHoldReleases.single().hold shouldBe first
            s.control.authorizedPaymentId shouldBe null
            val reopened = s.control.activeIncidentHolds.single()
            reopened.occurrence shouldBe 2
            reopened.sourceObservation shouldBe refOf(s, "inq-late-b")
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
            s.usage.reservationOf(s.authorization.id)?.state shouldBe LimitReservationState.FORCED
        }
        Then("a re-report of A absorbed (same terms, another source) is not explained by NO_EFFECT: occurrence 2 sourced from that re-report") {
            val s = absorbedSecondApproval(bTerms = approvedTerms())
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.NO_EFFECT_CONFIRMED), s.authorization, s.usage)
            s.control.activeIncidentHolds.single().sourceObservation shouldBe refOf(s, "inq-late-b")
        }
        Then("guard: nothing absorbed → the NO_EFFECT release opens nothing, the forced limit is RELEASED and a new payment is allowed") {
            val s = AuthorizationFixture.declined().also { apply(it, observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late-a")) }
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.NO_EFFECT_CONFIRMED), s.authorization, s.usage)
            s.control.isOnIncidentHold() shouldBe false
            s.control.incidentHolds.size shouldBe 1
            s.usage.reservationOf(s.authorization.id)?.state shouldBe LimitReservationState.RELEASED
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.CreateNewPayment(2)
        }
    }

    Given("nothing absorbed is left unexplained") {
        Then("a re-report of A's approval (same full terms from another source) is explained by the REAL release: no new hold, the forced limit is CONSUMED") {
            val s = absorbedSecondApproval(bTerms = approvedTerms())
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), s.authorization, s.usage)
            s.control.isOnIncidentHold() shouldBe false
            s.control.incidentHolds.size shouldBe 1
            s.usage.reservationOf(s.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
        }
        Then("a hold with no absorbed approval released as REAL opens nothing") {
            val s = AuthorizationFixture.declined().also { apply(it, observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late-a")) }
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), s.authorization, s.usage)
            s.control.isOnIncidentHold() shouldBe false
            s.control.incidentHolds.size shouldBe 1
        }
    }
})

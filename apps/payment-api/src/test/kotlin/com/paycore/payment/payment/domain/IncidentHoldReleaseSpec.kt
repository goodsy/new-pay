package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.apply
import com.paycore.payment.payment.domain.AuthorizationFixture.observation
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 사건 보류와 해제(D-AUTH-002, 명세 §8): 자동 보상취소 없음, 해제는 2인 승인(요청자 ≠ 승인자) + 재대사 MATCH 참조, 보류·해제 기록 append-only,
 * 하나 해제가 다른 보류를 없애지 않음, 실제 기관 승인이 확인된 주문은 해제 뒤에도 새 Payment 금지(§17-1 결함 수정).
 * 운영자 인증·역할(Q-ADMIN-AUTH)과 MATCH 증거 형식(RECON-CONTRACT-001)은 범위 밖이다.
 */
class IncidentHoldReleaseSpec : BehaviorSpec({

    val requester = OperatorId("demo-operator-a")
    val approver = OperatorId("demo-operator-b")

    fun release(hold: IncidentHold, finding: HoldReleaseFinding, by: OperatorId = requester, approvedBy: OperatorId = approver) =
        IncidentHoldRelease(
            hold, by, approvedBy, "recon-match-demo-1", finding, "demo: re-reconciled", startedAt.plusSeconds(3_600),
            correctionRef = if (finding == HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED) "demo-cancel-0001" else null,
        )

    /** D-AUTH-011: 1번 확정 거절 → 2번 승인 시작 → 1번 늦은 승인(hold) → 2번 승인 확정. 두 승인이 모두 실제일 수 있는 주문. */
    fun doubleApprovalOrder(): Pair<AuthorizationScenario, AuthorizationScenario> {
        val first = AuthorizationFixture.declined()
        val second = PaymentFixture.requested(n = 2, slotGeneration = 2)
        first.control.occupy(second, first.payment)
        val gen2 = AuthorizationFixture.startedOn(OrderPaymentScenario(first.control, second, listOf(first.payment, second)), 2, first.usage)
        gen2.authorization.beginDispatch(gen2.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
        apply(first, observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
        apply(gen2, observation(InstitutionOutcome.APPROVED, attempt = gen2.attemptId), newTransactionId = PaymentTransactionId("demo-ptx-0002"))
        return first to gen2
    }

    /** 1번 시도 확정 거절 → 늦은 승인으로 hold(SUCCESS_AFTER_TERMINATION). */
    fun lateApprovalOnDeclined(): AuthorizationScenario = AuthorizationFixture.declined().also {
        apply(it, observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
    }

    Given("the release record itself") {
        Then("the requester must not approve, and a MATCH reference and a reason are required") {
            val hold = IncidentHold.reconstitute(OrderFixture.paymentId(1), IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null)
            shouldThrow<IllegalArgumentException> { release(hold, HoldReleaseFinding.NO_EFFECT_CONFIRMED, by = requester, approvedBy = requester) }
            shouldThrow<IllegalArgumentException> {
                IncidentHoldRelease(hold, requester, approver, " ", HoldReleaseFinding.NO_EFFECT_CONFIRMED, "r", startedAt)
            }
            shouldThrow<IllegalArgumentException> {
                IncidentHoldRelease(hold, requester, approver, "recon-match-demo-1", HoldReleaseFinding.NO_EFFECT_CONFIRMED, "", startedAt)
            }
        }
    }

    Given("an order on hold because a confirmed decline was followed by a late approval") {
        Then("re-reconciliation confirmed a real approval: release keeps the hold record and the order now has success history (no new payment)") {
            val s = lateApprovalOnDeclined()
            val hold = s.control.activeIncidentHolds.single()
            s.control.releaseIncidentHold(release(hold, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED))
            s.control.isOnIncidentHold() shouldBe false
            s.control.incidentHolds shouldBe listOf(hold)
            s.control.incidentHoldReleases.single().approvedBy shouldBe approver
            s.control.authorizedPaymentId shouldBe s.payment.id
            s.payment.status shouldBe PaymentStatus.FAILED
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe
                PaymentSlotDecision.Blocked(BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED, s.payment.id)
        }
        Then("re-reconciliation confirmed no effect: the original safe termination stands and a new payment is allowed (D-CPD-002)") {
            val s = lateApprovalOnDeclined()
            s.control.releaseIncidentHold(release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.NO_EFFECT_CONFIRMED))
            s.control.authorizedPaymentId shouldBe null
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.CreateNewPayment(2)
        }
        Then("a hold is released once; an unknown hold cannot be released; a failed release changes nothing") {
            val s = lateApprovalOnDeclined()
            val hold = s.control.activeIncidentHolds.single()
            shouldThrow<IncidentHoldReleaseRejectedException> {
                s.control.releaseIncidentHold(release(IncidentHold.reconstitute(s.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT, 1, null), HoldReleaseFinding.NO_EFFECT_CONFIRMED))
            }
            s.control.isOnIncidentHold() shouldBe true
            s.control.releaseIncidentHold(release(hold, HoldReleaseFinding.NO_EFFECT_CONFIRMED))
            shouldThrow<IncidentHoldReleaseRejectedException> { s.control.releaseIncidentHold(release(hold, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED)) }
            s.control.authorizedPaymentId shouldBe null
            s.control.incidentHoldReleases.size shouldBe 1
        }
        Then("after a NO_EFFECT release a new success report opens a NEW hold (occurrence 2), never rewriting the released one (D-AUTH-012)") {
            val s = lateApprovalOnDeclined()
            val first = s.control.activeIncidentHolds.single()
            s.control.releaseIncidentHold(release(first, HoldReleaseFinding.NO_EFFECT_CONFIRMED))
            s.control.reportSuccessAfterTermination(s.payment) shouldBe true
            s.control.reportSuccessAfterTermination(s.payment) shouldBe true // 활성 hold가 있으면 멱등
            s.control.incidentHolds shouldBe listOf(first, IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null))
            s.control.incidentHoldReleases.single().hold shouldBe first
            s.control.isOnIncidentHold() shouldBe true
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
            // 새 hold도 2인 승인 + MATCH로만 해제한다.
            shouldThrow<IllegalArgumentException> {
                release(IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null), HoldReleaseFinding.NO_EFFECT_CONFIRMED, by = requester, approvedBy = requester)
            }
            s.control.releaseIncidentHold(release(IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null), HoldReleaseFinding.NO_EFFECT_CONFIRMED))
            s.control.isOnIncidentHold() shouldBe false
        }
        Then("after a REAL release a new success report also opens a NEW hold (occurrence 2) and blocks the order again (D-AUTH-013)") {
            val s = lateApprovalOnDeclined()
            val first = s.control.activeIncidentHolds.single()
            s.control.releaseIncidentHold(release(first, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED))
            s.control.reportSuccessAfterTermination(s.payment) shouldBe true
            s.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null))
            s.control.incidentHoldReleases.single().hold shouldBe first
            s.control.decide(OrderFixture.originalIntent, s.payment) shouldBe PaymentSlotDecision.Blocked(BlockReason.INCIDENT_HOLD, s.payment.id)
            shouldThrow<IllegalArgumentException> {
                release(IncidentHold.reconstitute(s.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null), HoldReleaseFinding.NO_EFFECT_CONFIRMED, by = approver, approvedBy = approver)
            }
        }
        Then("stored hold occurrences per (payment, reason) must be 1..n") {
            val c = OrderPaymentControlFixture.openedWithRequestedPayment().control
            shouldThrow<IllegalArgumentException> {
                OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, null,
                    listOf(IncidentHold.reconstitute(OrderFixture.paymentId(1), IncidentReason.SUCCESS_AFTER_TERMINATION, 2, null)), emptyList())
            }
        }
    }

    Given("an order with two holds") {
        Then("releasing one keeps the other active (one release never clears another hold)") {
            val s = AuthorizationFixture.authorized()
            apply(s, observation(InstitutionOutcome.DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-1"))
            val conflict = s.control.activeIncidentHolds.single()
            // 같은 주문의 다른 사건 이유(교차 검증용): 승인된 Payment에 대해 늦은 성공 hold는 생길 수 없으므로 복원으로 두 번째 hold를 둔다.
            val restored = OrderPaymentControl.reconstitute(
                s.control.orderKey, s.control.fixedIntent, s.control.currentPaymentId, s.control.generation, s.control.authorizedPaymentId,
                listOf(conflict, IncidentHold.reconstitute(OrderFixture.paymentId(7), IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null)), emptyList(),
            )
            restored.releaseIncidentHold(release(conflict, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED))
            restored.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(OrderFixture.paymentId(7), IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null))
            restored.isOnIncidentHold() shouldBe true
        }
        Then("REAL_APPROVAL_CONFIRMED for a payment other than the recorded success is rejected without changes") {
            val s = AuthorizationFixture.authorized()
            val other = IncidentHold.reconstitute(OrderFixture.paymentId(7), IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null)
            val restored = OrderPaymentControl.reconstitute(
                s.control.orderKey, s.control.fixedIntent, s.control.currentPaymentId, s.control.generation, s.control.authorizedPaymentId, listOf(other), emptyList(),
            )
            shouldThrow<IncidentHoldReleaseRejectedException> { restored.releaseIncidentHold(release(other, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED)) }
            restored.isOnIncidentHold() shouldBe true
            restored.authorizedPaymentId shouldBe s.payment.id
        }
    }

    Given("stored releases") {
        Then("reconstitute keeps released holds inactive and rejects releases of unknown holds or double releases") {
            val s = lateApprovalOnDeclined()
            val hold = s.control.activeIncidentHolds.single()
            val r = release(hold, HoldReleaseFinding.NO_EFFECT_CONFIRMED)
            val c = s.control
            OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, null, listOf(hold), listOf(r))
                .isOnIncidentHold() shouldBe false
            shouldThrow<IllegalArgumentException> {
                OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, null, emptyList(), listOf(r))
            }
            shouldThrow<IllegalArgumentException> {
                OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, null, listOf(hold), listOf(r, r))
            }
        }
    }

    Given("a double approval: earlier generation late approval on hold, current generation authorized (D-AUTH-011)") {
        Then("the correction reference is required exactly for DOUBLE_APPROVAL_CORRECTED (with MATCH and two people)") {
            val hold = IncidentHold.reconstitute(OrderFixture.paymentId(1), IncidentReason.SUCCESS_AFTER_TERMINATION, 1, null)
            shouldThrow<IllegalArgumentException> {
                IncidentHoldRelease(hold, requester, approver, "recon-match-demo-1", HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED, "r", startedAt)
            }
            shouldThrow<IllegalArgumentException> {
                IncidentHoldRelease(hold, requester, approver, "recon-match-demo-1", HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED, "r", startedAt, correctionRef = " ")
            }
            shouldThrow<IllegalArgumentException> {
                IncidentHoldRelease(hold, requester, approver, "recon-match-demo-1", HoldReleaseFinding.NO_EFFECT_CONFIRMED, "r", startedAt, correctionRef = "demo-cancel-0001")
            }
            shouldThrow<IllegalArgumentException> { release(hold, HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED, by = requester, approvedBy = requester) }
        }
        Then("release with the correction keeps the current generation as the order's success history; new payments stay forbidden; the forced limit is consumed") {
            val (first, gen2) = doubleApprovalOrder()
            val hold = first.control.activeIncidentHolds.single()
            IncidentHoldSettlement.release(first.control, release(hold, HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED), first.authorization, first.usage)
            first.control.isOnIncidentHold() shouldBe false
            first.control.authorizedPaymentId shouldBe gen2.payment.id
            first.control.incidentHoldReleases.single().correctionRef shouldBe "demo-cancel-0001"
            first.control.decide(OrderFixture.originalIntent, gen2.payment) shouldBe
                PaymentSlotDecision.Blocked(BlockReason.AUTHORIZED_NEW_ORDER_REQUIRED, gen2.payment.id)
            first.usage.reservationOf(first.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
        }
        Then("REAL_APPROVAL_CONFIRMED is refused when another payment is the recorded success; nothing changes") {
            val (first, gen2) = doubleApprovalOrder()
            val hold = first.control.activeIncidentHolds.single()
            shouldThrow<IncidentHoldReleaseRejectedException> {
                IncidentHoldSettlement.release(first.control, release(hold, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), first.authorization, first.usage)
            }
            first.control.isOnIncidentHold() shouldBe true
            first.control.authorizedPaymentId shouldBe gen2.payment.id
            first.usage.reservationOf(first.authorization.id)?.state shouldBe LimitReservationState.FORCED
        }
        Then("DOUBLE_APPROVAL_CORRECTED is refused when the order has no other authorized payment") {
            val s = lateApprovalOnDeclined()
            shouldThrow<IncidentHoldReleaseRejectedException> {
                IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED), s.authorization, s.usage)
            }
            s.control.isOnIncidentHold() shouldBe true
        }
    }

    Given("hold release settles the forced limit re-occupation (D-AUTH-010)") {
        Then("NO_EFFECT releases it again, REAL_APPROVAL consumes it") {
            val noEffect = lateApprovalOnDeclined()
            noEffect.usage.reservationOf(noEffect.authorization.id)?.state shouldBe LimitReservationState.FORCED
            IncidentHoldSettlement.release(noEffect.control, release(noEffect.control.activeIncidentHolds.single(), HoldReleaseFinding.NO_EFFECT_CONFIRMED), noEffect.authorization, noEffect.usage)
            noEffect.usage.reservationOf(noEffect.authorization.id)?.state shouldBe LimitReservationState.RELEASED
            val real = lateApprovalOnDeclined()
            IncidentHoldSettlement.release(real.control, release(real.control.activeIncidentHolds.single(), HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), real.authorization, real.usage)
            real.usage.reservationOf(real.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
            real.control.authorizedPaymentId shouldBe real.payment.id
        }
        // AUTH-001 m-11(D-AUTH-010 "REAL이면 소비"): 같은 Payment의 해제 기록 중 실제 승인 판정이 하나라도 있으면 기관 승인이 있었으므로 FORCED는 CONSUMED.
        Then("two holds of one payment released with different findings (REAL first, NO_EFFECT last): the forced limit is CONSUMED, not RELEASED (m-11)") {
            val s = lateApprovalOnDeclined()
            // 같은 늦은 승인 관찰 키로 다른 내용이 오면 KEY_CONFLICT → 같은 Payment에 CONFLICTING hold가 하나 더 생긴다.
            apply(s, observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late",
                terms = AuthorizationFixture.approvedTerms().copy(approvalNumber = "A0002")))
            s.control.activeIncidentHolds.size shouldBe 2
            val success = s.control.activeIncidentHolds.single { it.reason == IncidentReason.SUCCESS_AFTER_TERMINATION }
            IncidentHoldSettlement.release(s.control, release(success, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED), s.authorization, s.usage)
            s.usage.reservationOf(s.authorization.id)?.state shouldBe LimitReservationState.FORCED
            IncidentHoldSettlement.release(s.control, release(s.control.activeIncidentHolds.single(), HoldReleaseFinding.NO_EFFECT_CONFIRMED), s.authorization, s.usage)
            s.usage.reservationOf(s.authorization.id)?.state shouldBe LimitReservationState.CONSUMED
            s.control.isOnIncidentHold() shouldBe false
        }
        Then("an authorization of another payment, or only one of authorization/usage, is a caller defect and changes nothing") {
            val s = lateApprovalOnDeclined()
            val hold = s.control.activeIncidentHolds.single()
            shouldThrow<IllegalArgumentException> {
                IncidentHoldSettlement.release(s.control, release(hold, HoldReleaseFinding.NO_EFFECT_CONFIRMED), s.authorization, null)
            }
            val otherDay = DailyLimitUsage.open(AuthorizationFixture.usageKey(startedAt.plusSeconds(86_400)))
            shouldThrow<IllegalArgumentException> {
                IncidentHoldSettlement.release(s.control, release(hold, HoldReleaseFinding.NO_EFFECT_CONFIRMED), s.authorization, otherDay)
            }
            val (first, gen2) = doubleApprovalOrder()
            shouldThrow<IllegalArgumentException> {
                IncidentHoldSettlement.release(first.control, release(first.control.activeIncidentHolds.single(), HoldReleaseFinding.NO_EFFECT_CONFIRMED), gen2.authorization, gen2.usage)
            }.message shouldBe "authorization must belong to the held payment"
            first.control.isOnIncidentHold() shouldBe true
            s.control.isOnIncidentHold() shouldBe true
        }
    }

    Given("stored releases must match the order's success history (review n-6)") {
        Then("REAL release needs the released payment as authorized payment; DOUBLE needs another one") {
            val s = lateApprovalOnDeclined()
            val c = s.control
            val hold = c.activeIncidentHolds.single()
            val real = release(hold, HoldReleaseFinding.REAL_APPROVAL_CONFIRMED)
            OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, s.payment.id, listOf(hold), listOf(real))
                .authorizedPaymentId shouldBe s.payment.id
            shouldThrow<IllegalArgumentException> {
                OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, null, listOf(hold), listOf(real))
            }
            val double = release(hold, HoldReleaseFinding.DOUBLE_APPROVAL_CORRECTED)
            shouldThrow<IllegalArgumentException> {
                OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, s.payment.id, listOf(hold), listOf(double))
            }
            OrderPaymentControl.reconstitute(c.orderKey, c.fixedIntent, c.currentPaymentId, c.generation, OrderFixture.paymentId(2), listOf(hold), listOf(double))
                .isOnIncidentHold() shouldBe false
        }
    }

    Given("conflicting result reports on the control") {
        Then("only payments whose authorization started can be reported, and a report is idempotent") {
            val requested = OrderPaymentControlFixture.openedWithRequestedPayment()
            shouldThrow<IllegalArgumentException> { requested.control.reportConflictingResult(requested.current) }
            val s = AuthorizationFixture.dispatching()
            s.control.reportConflictingResult(s.payment)
            s.control.reportConflictingResult(s.payment)
            s.control.incidentHolds shouldBe listOf(IncidentHold.reconstitute(s.payment.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT, 1, null))
        }
    }
})

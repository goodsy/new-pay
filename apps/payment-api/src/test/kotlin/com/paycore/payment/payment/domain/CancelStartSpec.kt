package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.CancellationFixture.command
import com.paycore.payment.payment.domain.InstitutionOutcome.APPROVED
import com.paycore.payment.payment.domain.InstitutionOutcome.DECLINED
import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.won
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 취소 시작 판정표(CANCEL-CONTRACT-001 §4.4·§11.1 CX 행, T4). 검사 순서: 대상 상태 → incident hold(D-CANCEL-001) → 진행 중 취소(D-CANCEL-003)
 * → 통화·반환 가능 금액(§4.3) → 기관 Capability(§4.2, D-CANCEL-004 — PG 기한 없음). 모든 검사를 통과할 때만 취소 OPEN + 반환 예약을 함께 만들고,
 * 거절은 어떤 Aggregate도 바꾸지 않는다. 취소는 신규 승인 권리·일 한도·주문 슬롯과 무관하다(K-3, D-AUTH-007, K-12). 기대값은 명세 표에서 직접 적는다.
 */
class CancelStartSpec : BehaviorSpec({

    fun rejected(result: CancelStartResult) = result.shouldBeInstanceOf<CancelStartResult.Rejected>().reason

    fun started(result: CancelStartResult) = result.shouldBeInstanceOf<CancelStartResult.Started>()

    Given("an authorized 10,000 KRW payment with no returns") {
        Then("CX-01 the whole amount at once is a FULL cancel: OPEN, PREPARED attempt, 10,000 reserved, 0 left, balance opened") {
            val s = AuthorizationFixture.authorized()
            val r = started(CancelStart.start(command(s, null, 10_000L.won())))
            r.balanceOpened shouldBe true
            r.cancellation.scope shouldBe CancelScope.FULL
            r.cancellation.status shouldBe CancellationStatus.OPEN
            r.cancellation.money shouldBe 10_000L.won()
            r.cancellation.initiator shouldBe CancellationFixture.merchant(1)
            r.balance.reservationOf(r.cancellation.id) shouldBe ReturnReservation(r.cancellation.id, 10_000L, ReturnReservationState.RESERVED)
            r.balance.cancellableMinor shouldBe 0L
        }
        Then("CX-02 a smaller amount is PARTIAL and leaves the rest cancellable") {
            val s = AuthorizationFixture.authorized()
            val r = started(CancelStart.start(command(s, null, 3_000L.won())))
            r.cancellation.scope shouldBe CancelScope.PARTIAL
            r.balance.cancellableMinor shouldBe 7_000L
        }
        Then("CX-06 another currency is rejected and nothing is created") {
            val s = AuthorizationFixture.authorized()
            rejected(CancelStart.start(command(s, null, 100L.usdCents()))) shouldBe CancelStartRejection.CURRENCY_MISMATCH
        }
        Then("CX-10 starting a cancel leaves the daily limit usage, the order's authorized history and the payment status untouched") {
            val s = AuthorizationFixture.authorized()
            val limitBefore = s.usage.reservations
            val occupiedBefore = s.usage.occupiedMinor
            started(CancelStart.start(command(s, null, 10_000L.won())))
            s.usage.reservations shouldBe limitBefore
            s.usage.occupiedMinor shouldBe occupiedBefore
            s.control.authorizedPaymentId shouldBe s.payment.id
            s.payment.status shouldBe PaymentStatus.AUTHORIZED
            s.authorization.status shouldBe AuthorizationStatus.AUTHORIZED
        }
        Then("CX-09 without a resolvable institution capability no cancel starts, not even a full one (fail-closed)") {
            val s = AuthorizationFixture.authorized()
            rejected(CancelStart.start(command(s, null, 10_000L.won(), capability = CancelCapabilityResolution.Unavailable))) shouldBe
                CancelStartRejection.CAPABILITY_UNAVAILABLE
        }
    }

    Given("returns already made (CX-03..05)") {
        Then("CX-03 after 3,000 completed the remaining 7,000 is a PARTIAL cancel (the last remainder)") {
            val c = CancellationFixture.partiallyCancelled(3_000L.won())
            val r = started(CancelStart.start(command(c.auth, c.balance, 7_000L.won(), n = 2)))
            r.balanceOpened shouldBe false
            r.cancellation.scope shouldBe CancelScope.PARTIAL
            r.balance.cancellableMinor shouldBe 0L
        }
        Then("CX-04 after 3,000 completed 7,001 is rejected and the balance is unchanged") {
            val c = CancellationFixture.partiallyCancelled(3_000L.won())
            rejected(CancelStart.start(command(c.auth, c.balance, 7_001L.won(), n = 2))) shouldBe CancelStartRejection.AMOUNT_EXCEEDS_BALANCE
            c.balance.reservations.size shouldBe 1
            c.balance.cancellableMinor shouldBe 7_000L
        }
        Then("CX-05 after a full return any further amount is ALREADY_FULLY_CANCELLED") {
            val c = CancellationFixture.partiallyCancelled(10_000L.won())
            rejected(CancelStart.start(command(c.auth, c.balance, 1L.won(), n = 2))) shouldBe CancelStartRejection.ALREADY_FULLY_CANCELLED
        }
        Then("CB-02 a declined (released) cancel is not a return: the whole amount afterwards is FULL") {
            val c = CancellationFixture.declined(CancellationFixture.paymentWithReservedReturn(3_000L.won()))
            val r = started(CancelStart.start(command(c.auth, c.balance, 10_000L.won(), n = 2)))
            r.cancellation.scope shouldBe CancelScope.FULL
        }
    }

    Given("payments that are not cancellable (CX-07: only a confirmed authorization transaction can be returned)") {
        Then("REQUESTED, AUTHORIZING, UNKNOWN, FAILED and EXPIRED are rejected with PAYMENT_NOT_CANCELLABLE") {
            val requested = AuthorizationFixture.readyToStart()
            val requestedScenario = AuthorizationScenario(requested.control, requested.payment, requested.session, requested.usage, AuthorizationFixture.authorized().authorization)
            rejected(CancelStart.start(command(requestedScenario, null, 1_000L.won(), authorization = null))) shouldBe CancelStartRejection.PAYMENT_NOT_CANCELLABLE

            val expired = AuthorizationFixture.readyToStart()
            expired.payment.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
            val expiredScenario = AuthorizationScenario(expired.control, expired.payment, expired.session, expired.usage, AuthorizationFixture.authorized().authorization)
            rejected(CancelStart.start(command(expiredScenario, null, 1_000L.won(), authorization = null))) shouldBe CancelStartRejection.PAYMENT_NOT_CANCELLABLE

            listOf(AuthorizationFixture.started(), AuthorizationFixture.unknownAfterTimeout(), AuthorizationFixture.declined()).forEach { s ->
                rejected(CancelStart.start(command(s, null, 1_000L.won()))) shouldBe CancelStartRejection.PAYMENT_NOT_CANCELLABLE
            }
        }
    }

    Given("an in-flight cancel on the same payment (CX-13, D-CANCEL-003)") {
        Then("an OPEN or UNKNOWN cancel blocks any new cancel, for merchants and operators alike; nothing changes") {
            val open = CancellationFixture.paymentWithReservedReturn(3_000L.won())
            rejected(CancelStart.start(command(open.auth, open.balance, 1_000L.won(), n = 2))) shouldBe CancelStartRejection.CANCEL_IN_PROGRESS
            rejected(CancelStart.start(command(open.auth, open.balance, 1_000L.won(), n = 2, initiator = CancellationFixture.operator(open.auth, 1_000L.won(), open.balance)))) shouldBe
                CancelStartRejection.CANCEL_IN_PROGRESS

            val unknown = CancellationFixture.paymentWithReservedReturn(3_000L.won())
            unknown.cancellation.beginDispatch(unknown.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION)
            unknown.cancellation.attempt(unknown.attemptId).markOutcomeUnknown(unknown.cancellation.id, UnknownCause.TIMEOUT)
            unknown.cancellation.markUnknown()
            rejected(CancelStart.start(command(unknown.auth, unknown.balance, 1_000L.won(), n = 2))) shouldBe CancelStartRejection.CANCEL_IN_PROGRESS
            unknown.balance.reservations.size shouldBe 1
            unknown.balance.cancellableMinor shouldBe 7_000L
        }
    }

    Given("incident holds (CX-14, D-CANCEL-001·005)") {
        Then("a hold on this payment refuses a merchant cancel with ORDER_ON_HOLD but lets an approved operator correction start") {
            val s = AuthorizationFixture.authorized()
            AuthorizationFixture.apply(s, AuthorizationFixture.observation(DECLINED, source = ObservationSource.NOTIFICATION, resultId = "ntf-contra")) shouldBe
                ResolutionOutcome.Incident(IncidentReason.CONFLICTING_INSTITUTION_RESULT)
            rejected(CancelStart.start(command(s, null, 3_000L.won()))) shouldBe CancelStartRejection.ORDER_ON_HOLD
            val op = CancellationFixture.operator(s, 3_000L.won(), null)
            val r = started(CancelStart.start(command(s, null, 3_000L.won(), initiator = op)))
            r.cancellation.initiator shouldBe op
            s.control.isOnIncidentHold() shouldBe true
        }
        Then("a hold only on another payment of the same order does not block the merchant cancel (double approval, D-AUTH-011)") {
            val (first, gen2) = CancellationFixture.authorizedSecondGeneration(lateApprovalOnFirst = true)
            val second = gen2.payment
            first.control.activeIncidentHolds.map { it.paymentId } shouldBe listOf(first.payment.id)

            val cancelGen2 = CancelStart.start(
                command(gen2, null, 10_000L.won(), providerRequest = CancellationFixture.cancelRequest(1, AuthorizationFixture.providerRequest.copy(requestId = "ar_demo0002"))),
            )
            started(cancelGen2).cancellation.paymentId shouldBe second.id
            // 늦은 승인 hold 대상(gen1)은 RECON이 거래를 기록하기 전에는 누구도 취소할 수 없다(D-CANCEL-011 — 기록 뒤에는 운영자 보정만, CX-27).
            rejected(CancelStart.start(command(first, null, 10_000L.won(), n = 3))) shouldBe CancelStartRejection.PAYMENT_NOT_CANCELLABLE
        }
    }

    Given("institution partial-cancel capability (CX-08, 범위 §1 '기관 지원 범위 내')") {
        Then("an installment payment whose profile does not support partial cancel refuses a partial amount but accepts the full amount") {
            val s = CancellationFixture.authorizedInstallment()
            val noPartial = CancelCapabilityResolution.Resolved(CancellationFixture.noInstallmentPartial)
            rejected(CancelStart.start(command(s, null, 3_000L.won(), capability = noPartial))) shouldBe CancelStartRejection.PARTIAL_CANCEL_NOT_SUPPORTED
            started(CancelStart.start(command(s, null, 10_000L.won(), capability = noPartial))).cancellation.scope shouldBe CancelScope.FULL
        }
        Then("a profile that supports installment partial cancel for credit cards accepts it") {
            val s = CancellationFixture.authorizedInstallment()
            started(CancelStart.start(command(s, null, 3_000L.won()))).cancellation.scope shouldBe CancelScope.PARTIAL
        }
        Then("the capability answers by plan and card type") {
            val cap = CancellationFixture.capability
            cap.supportsPartial(PaymentPlan.LumpSum, CardType.CHECK) shouldBe true
            cap.supportsPartial(PaymentPlan.Installment(3), CardType.CREDIT) shouldBe true
            cap.supportsPartial(PaymentPlan.Installment(3), CardType.CHECK) shouldBe false
            shouldThrow<IllegalArgumentException> { cap.copy(version = "") }
        }
    }

    Given("inputs that do not belong together (CX-12 — defects, not business rejections)") {
        Then("a balance of another payment, a cancel request off the original route, or a reused cancellation id throw and change nothing") {
            val a = CancellationFixture.partiallyCancelled(3_000L.won())
            val other = CancellationFixture.authorizedSecondGeneration().second
            shouldThrow<IllegalArgumentException> { CancelStart.start(command(other, a.balance, 1_000L.won(), n = 2)) }
            shouldThrow<IllegalArgumentException> {
                CancelStart.start(command(other, null, 1_000L.won(), providerRequest = CancellationFixture.cancelRequest().copy(institution = "SIM-VAN-B")))
            }
            shouldThrow<IllegalArgumentException> {
                CancelStart.start(command(other, null, 1_000L.won(), providerRequest = CancellationFixture.cancelRequest().copy(institutionAccount = "sim-acct-99")))
            }
            shouldThrow<IllegalArgumentException> { CancelStart.start(command(a.auth, a.balance, 1_000L.won(), n = 1)) }
            a.balance.reservations.size shouldBe 1
            a.balance.cancellableMinor shouldBe 7_000L
        }
    }

    Given("operator correction re-checks in the owning service (D-CANCEL-005, D-ADMIN-007, review m-3·m-4)") {
        Then("requester == approver, a blank reason or a blank basis reference is rejected and nothing is created") {
            val s = AuthorizationFixture.authorized()
            val m = 3_000L.won()
            rejected(CancelStart.start(command(s, null, m, initiator = CancellationFixture.operator(s, m, null, approvedBy = OperatorId("demo-op-requester"))))) shouldBe
                CancelStartRejection.OPERATOR_SAME_PERSON
            rejected(CancelStart.start(command(s, null, m, initiator = CancellationFixture.operator(s, m, null, reason = " ")))) shouldBe
                CancelStartRejection.OPERATOR_REASON_MISSING
            rejected(
                CancelStart.start(command(s, null, m, initiator = CancellationFixture.operator(s, m, null, purpose = OperatorCancelPurpose.IncidentOrReconCorrection("")))),
            ) shouldBe CancelStartRejection.OPERATOR_BASIS_MISSING
        }
        // mid-review #1 m-3·F-16: 사유 ≤ 500자·근거 참조 ≤ 200자(DB 열과 같은 상한), 깨진 UTF-16(짝 없는 서로게이트)은 업무 거절이다 — 저장 단계 1406·1366 결함이 아니다.
        // 경계 값(500·200)은 시작된다. S5 Use Case는 이 거절을 D-CANCEL1-008 감사 행으로 남긴다.
        Then("an over-long or malformed reason or basis reference is a business rejection; the limits themselves are accepted") {
            val s = AuthorizationFixture.authorized()
            val m = 3_000L.won()
            rejected(CancelStart.start(command(s, null, m, initiator = CancellationFixture.operator(s, m, null, reason = "가".repeat(501))))) shouldBe
                CancelStartRejection.OPERATOR_REASON_INVALID
            // 깨진 문구는 Fixture의 승인 지문 계산(UTF-8 인코딩)부터 막히므로 지문은 임의 값으로 둔다 — 형식 검사가 대상 대조보다 먼저다.
            fun malformed(reason: String, purpose: OperatorCancelPurpose) = CancelInitiator.Operator(
                OperatorId("demo-op-requester"), OperatorId("demo-op-approver"), CancellationFixture.approvalId(), reason, purpose, "oct1:unused",
            )
            rejected(CancelStart.start(command(s, null, m, initiator = malformed("bad\uD800", CancellationFixture.correctionPurpose)))) shouldBe
                CancelStartRejection.OPERATOR_REASON_INVALID
            rejected(
                CancelStart.start(command(s, null, m, initiator = CancellationFixture.operator(s, m, null, purpose = OperatorCancelPurpose.IncidentOrReconCorrection("c".repeat(201))))),
            ) shouldBe CancelStartRejection.OPERATOR_BASIS_INVALID
            rejected(CancelStart.start(command(s, null, m, initiator = malformed("사유", OperatorCancelPurpose.MerchantTerminatedObligation("t\uDC00"))))) shouldBe
                CancelStartRejection.OPERATOR_BASIS_INVALID
            CancelStart.start(
                command(s, null, m, initiator = CancellationFixture.operator(s, m, null, reason = "가".repeat(500), purpose = OperatorCancelPurpose.IncidentOrReconCorrection("c".repeat(200)))),
            ).shouldBeInstanceOf<CancelStartResult.Started>()
        }
        Then("operator and approval identifiers and the payment id are bounded value types (≤ 64, well-formed) — never reaching a 64-character column overflow") {
            listOf<() -> Any>(
                { OperatorId("o".repeat(65)) }, { OperatorApprovalId("a".repeat(65)) }, { PaymentId("p".repeat(65)) },
                { OperatorId("bad\uD800") }, { OperatorApprovalId("") }, { PaymentId("x\uDC00") },
            ).forEach { make -> shouldThrow<IllegalArgumentException> { make() } }
            PaymentId("p".repeat(64)).value.length shouldBe 64
        }
        Then("an approval for another amount or for an older balance revision is stale") {
            val s = AuthorizationFixture.authorized()
            val approvedFor2000 = CancellationFixture.operator(s, 2_000L.won(), null)
            rejected(CancelStart.start(command(s, null, 3_000L.won(), initiator = approvedFor2000))) shouldBe CancelStartRejection.OPERATOR_TARGET_STALE

            val c = CancellationFixture.partiallyCancelled(3_000L.won())
            val approvedBeforeTheMerchantCancel = CancellationFixture.operator(c.auth, 1_000L.won(), c.balance, n = 2, approvedRevision = 0L)
            rejected(CancelStart.start(command(c.auth, c.balance, 1_000L.won(), n = 2, initiator = approvedBeforeTheMerchantCancel))) shouldBe
                CancelStartRejection.OPERATOR_TARGET_STALE
            c.balance.reservations.size shouldBe 1
        }
        Then("the approved target also binds approvalId, requester, approver, purpose type and purpose reference: changing any one is stale; the reason text is not bound (review m-C)") {
            val s = AuthorizationFixture.authorized()
            val m = 3_000L.won()
            val approved = CancellationFixture.operator(s, m, null)
            listOf(
                approved.copy(approvalId = CancellationFixture.approvalId(9)),
                approved.copy(requestedBy = OperatorId("demo-op-third")),
                approved.copy(approvedBy = OperatorId("demo-op-third")),
                approved.copy(purpose = OperatorCancelPurpose.MerchantTerminatedObligation("recon-case-demo-0001")),
                approved.copy(purpose = OperatorCancelPurpose.IncidentOrReconCorrection("recon-case-demo-0002")),
            ).forEach { changed ->
                rejected(CancelStart.start(command(s, null, m, initiator = changed))) shouldBe CancelStartRejection.OPERATOR_TARGET_STALE
            }
            started(CancelStart.start(command(s, null, m, initiator = approved.copy(reason = "다른 사유 문구"))))
        }
        Then("a merchant-terminated cancel obligation is an allowed operator purpose") {
            val s = AuthorizationFixture.authorized()
            val op = CancellationFixture.operator(s, 10_000L.won(), null, purpose = OperatorCancelPurpose.MerchantTerminatedObligation("termination-demo-0001"))
            started(CancelStart.start(command(s, null, 10_000L.won(), initiator = op))).cancellation.initiator shouldBe op
        }
    }

    Given("no PG-owned cancel deadline (D-CANCEL-004)") {
        Then("an authorization from long ago still starts a cancel; an expired institution window comes back as an institution decline") {
            val s = AuthorizationFixture.authorized()
            val r = CancelStart.start(command(s, null, 3_000L.won()).copy(at = AuthorizationFixture.startedAt.plus(java.time.Duration.ofDays(3 * 365))))
            started(r).cancellation.openedAt shouldBe AuthorizationFixture.startedAt.plus(java.time.Duration.ofDays(3 * 365))
        }
    }

    Given("a late cancel success after a decline (D-CANCEL-006 sequence)") {
        Then("FORCED + hold → the merchant gets ORDER_ON_HOLD; an operator cancel stays within the cancellable amount that excludes FORCED") {
            val c = CancellationFixture.lateCancelSuccessAfterDecline(3_000L.won())
            c.balance.forcedMinor shouldBe 3_000L
            c.auth.control.hasActiveHold(c.auth.payment.id, IncidentReason.CANCEL_RESULT_CONFLICT) shouldBe true
            rejected(CancelStart.start(command(c.auth, c.balance, 1_000L.won(), n = 2))) shouldBe CancelStartRejection.ORDER_ON_HOLD
            rejected(CancelStart.start(command(c.auth, c.balance, 7_001L.won(), n = 2, initiator = CancellationFixture.operator(c.auth, 7_001L.won(), c.balance, n = 2)))) shouldBe
                CancelStartRejection.AMOUNT_EXCEEDS_BALANCE
            val r = started(CancelStart.start(command(c.auth, c.balance, 7_000L.won(), n = 2, initiator = CancellationFixture.operator(c.auth, 7_000L.won(), c.balance, n = 2))))
            r.cancellation.scope shouldBe CancelScope.PARTIAL
            r.balance.cancellableMinor shouldBe 0L
        }
        Then("a zero cancellable amount that comes only from FORCED (declined full cancel + late success, nothing completed) is AMOUNT_EXCEEDS_BALANCE, not ALREADY_FULLY_CANCELLED (review m-A)") {
            val c = CancellationFixture.lateCancelSuccessAfterDecline(10_000L.won())
            c.balance.completedMinor shouldBe 0L
            c.balance.status shouldBe ReturnStatus.NONE
            c.balance.cancellableMinor shouldBe 0L
            rejected(CancelStart.start(command(c.auth, c.balance, 1L.won(), n = 2, initiator = CancellationFixture.operator(c.auth, 1L.won(), c.balance, n = 2)))) shouldBe
                CancelStartRejection.AMOUNT_EXCEEDS_BALANCE
        }
        Then("after the H-1 over-return evidence nothing more is cancellable, even for an operator") {
            val c = CancellationFixture.paymentWithForcedReturnAfterLateCancelSuccess()
            rejected(CancelStart.start(command(c.auth, c.balance, 1L.won(), n = 3, initiator = CancellationFixture.operator(c.auth, 1L.won(), c.balance, n = 3)))) shouldBe
                CancelStartRejection.ALREADY_FULLY_CANCELLED
        }
        Then("releasing a cancel-result hold is refused until RECON-CONTRACT-001 defines its findings (fail-closed, review m-1)") {
            val c = CancellationFixture.lateCancelSuccessAfterDecline(3_000L.won())
            val hold = c.auth.control.activeIncidentHolds.single()
            val release = IncidentHoldRelease(hold, OperatorId("demo-op-a"), OperatorId("demo-op-b"), "recon-match-demo", HoldReleaseFinding.NO_EFFECT_CONFIRMED, "검토", CancellationFixture.at)
            shouldThrow<IncidentHoldReleaseRejectedException> { c.auth.control.releaseIncidentHold(release) }
            c.auth.control.isOnIncidentHold() shouldBe true
        }
    }

    Given("several partial cancels across a later policy change (CB-08, structural: there is no policy input to change)") {
        Then("each cancellation carries the original authorization's policy and entitlement versions, never a current one") {
            val c = CancellationFixture.partiallyCancelled(3_000L.won())
            val second = started(CancelStart.start(command(c.auth, c.balance, 2_000L.won(), n = 2))).cancellation
            listOf(c.cancellation, second).forEach {
                it.policyAssignmentVersion shouldBe AuthorizationFixture.admission.policyAssignmentVersion
                it.entitlementVersion shouldBe AuthorizationFixture.admission.entitlementVersion
            }
        }
    }

    Given("a double-approval hold on the payment that holds a confirmed authorization transaction (CX-22, D-CANCEL-010)") {
        Then("the merchant is refused with ORDER_ON_HOLD but an operator correction cancel starts (the correction path is not blocked)") {
            // gen1 확정 거절 → gen2 timeout → gen1 늦은 승인 hold → REAL 해제 → gen2 승인 = gen2 AUTHORIZED + 이중 승인 CONFLICTING hold(round2 M-3).
            val first = AuthorizationFixture.declined()
            val second = PaymentFixture.requested(n = 2, slotGeneration = 2)
            first.control.occupy(second, first.payment)
            val gen2 = AuthorizationFixture.startedOn(OrderPaymentScenario(first.control, second, listOf(first.payment, second)), 2, first.usage)
            gen2.authorization.beginDispatch(gen2.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
            AuthorizationResolution.markOutcomeUnknown(gen2.payment, gen2.authorization, gen2.attemptId, UnknownCause.TIMEOUT)
            AuthorizationFixture.apply(first, AuthorizationFixture.observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
            IncidentHoldSettlement.release(
                first.control,
                IncidentHoldRelease(first.control.activeIncidentHolds.single(), OperatorId("demo-operator-a"), OperatorId("demo-operator-b"),
                    "recon-match-demo-1", HoldReleaseFinding.REAL_APPROVAL_CONFIRMED, "demo", AuthorizationFixture.startedAt.plusSeconds(600)),
                first.authorization, first.usage,
            )
            AuthorizationFixture.apply(gen2, AuthorizationFixture.observation(APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-gen2", attempt = gen2.attemptId),
                newTransactionId = PaymentTransactionId("demo-ptx-0002"))
            first.control.activeIncidentHolds shouldBe listOf(IncidentHold.reconstitute(second.id, IncidentReason.CONFLICTING_INSTITUTION_RESULT, 1, null))
            gen2.authorization.transaction.shouldBeInstanceOf<AuthorizationTransaction>()

            rejected(CancelStart.start(command(gen2, null, 10_000L.won()))) shouldBe CancelStartRejection.ORDER_ON_HOLD
            val op = CancellationFixture.operator(gen2, 10_000L.won(), null, purpose = OperatorCancelPurpose.IncidentOrReconCorrection("recon-case-double-0001"))
            started(CancelStart.start(command(gen2, null, 10_000L.won(), initiator = op))).cancellation.paymentId shouldBe second.id
        }
    }

    Given("the late-confirmed P1 after RECON recorded its transaction under the hold (CX-27, D-CANCEL-010·011)") {
        Then("an operator correction cancel starts, a merchant cancel is PAYMENT_NOT_CANCELLABLE, and P1 stays FAILED with the order history unchanged") {
            val (p1, p2) = CancellationFixture.lateConfirmedFirstGeneration()
            p1.payment.status shouldBe PaymentStatus.FAILED
            p1.authorization.status shouldBe AuthorizationStatus.FAILED
            p1.authorization.transaction.shouldBeInstanceOf<AuthorizationTransaction>()
            p1.control.hasActiveHold(p1.payment.id, IncidentReason.SUCCESS_AFTER_TERMINATION) shouldBe true

            rejected(CancelStart.start(command(p1, null, 10_000L.won(), n = 3))) shouldBe CancelStartRejection.PAYMENT_NOT_CANCELLABLE
            val op = CancellationFixture.operator(p1, 10_000L.won(), null, n = 3, purpose = OperatorCancelPurpose.IncidentOrReconCorrection("recon-case-double-0001"))
            val r = started(CancelStart.start(command(p1, null, 10_000L.won(), n = 3, initiator = op)))
            r.cancellation.paymentId shouldBe p1.payment.id
            r.cancellation.scope shouldBe CancelScope.FULL
            r.cancellation.originalTransactionId shouldBe PaymentTransactionId("demo-ptx-late-0001")
            p1.payment.status shouldBe PaymentStatus.FAILED
            p1.control.authorizedPaymentId shouldBe p2.payment.id

            r.cancellation.beginDispatch(r.cancellation.attempts.single().id, CancellationFixture.ROUTE_PROFILE_VERSION)
            val c = CancelScenario(p1, r.balance, r.cancellation)
            CancellationFixture.applyCancel(c, CancellationFixture.cancelObservation(c, InstitutionOutcome.APPROVED), n = 3)
                .shouldBeInstanceOf<CancelResolutionOutcome.Completed>().statusAfter shouldBe ReturnStatus.FULLY_CANCELLED
            p1.payment.status shouldBe PaymentStatus.FAILED
            p1.authorization.status shouldBe AuthorizationStatus.FAILED
            p1.control.authorizedPaymentId shouldBe p2.payment.id
            p2.payment.status shouldBe PaymentStatus.AUTHORIZED
        }
        Then("an operator cancel on a FAILED payment without a recorded transaction is PAYMENT_NOT_CANCELLABLE") {
            val d = AuthorizationFixture.declined()
            val op = CancellationFixture.operator(d, 1_000L.won(), null)
            rejected(CancelStart.start(command(d, null, 1_000L.won(), initiator = op))) shouldBe CancelStartRejection.PAYMENT_NOT_CANCELLABLE
        }
        Then("the RECON recording transition only adds a matching transaction to a FAILED authorization once") {
            val (p1, _) = CancellationFixture.lateConfirmedFirstGeneration()
            val tx = checkNotNull(p1.authorization.transaction)
            shouldThrow<InvalidAuthorizationTransitionException> { p1.authorization.recordLateApprovalByReconciliation(tx.copy(id = PaymentTransactionId("demo-ptx-late-0002"))) }
            val authorized = AuthorizationFixture.authorized()
            shouldThrow<InvalidAuthorizationTransitionException> {
                authorized.authorization.recordLateApprovalByReconciliation(checkNotNull(authorized.authorization.transaction).copy(id = PaymentTransactionId("demo-ptx-x")))
            }
            // 진행 중(UNKNOWN) 승인에는 기록할 수 없다 — 그 확정은 정상 Resolution 경로다.
            val unknown = AuthorizationFixture.unknownAfterTimeout()
            shouldThrow<InvalidAuthorizationTransitionException> {
                unknown.authorization.recordLateApprovalByReconciliation(tx.copy(authorizationId = unknown.authorization.id, paymentId = unknown.payment.id))
            }
            // 기록된 기관 관찰이 없는 승인은 거래로 만들 수 없다(대사·조회 관찰이 근거).
            val declinedWithoutLateObservation = AuthorizationFixture.declined()
            shouldThrow<IllegalArgumentException> {
                declinedWithoutLateObservation.authorization.recordLateApprovalByReconciliation(
                    tx.copy(authorizationId = declinedWithoutLateObservation.authorization.id, paymentId = declinedWithoutLateObservation.payment.id),
                )
            }
            val declined = AuthorizationFixture.declined()
            shouldThrow<IllegalArgumentException> {
                declined.authorization.recordLateApprovalByReconciliation(
                    tx.copy(authorizationId = declined.authorization.id, paymentId = declined.payment.id, terms = AuthorizationFixture.approvedTerms(money = 9_000L.won())),
                )
            }
        }
    }
})

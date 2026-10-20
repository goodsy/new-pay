package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.MoneyFixture.USD
import com.paycore.payment.payment.domain.MoneyFixture.usdCents
import com.paycore.payment.payment.domain.MoneyFixture.won
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 취소를 시작한 상태(승인 시나리오 + 반환 잔액 + 취소 operation). */
class CancelScenario(
    val auth: AuthorizationScenario,
    val balance: ReturnBalance,
    val cancellation: PaymentCancellation,
) {
    val attemptId: CancelAttemptId
        get() = cancellation.attempts.single().id
}

/**
 * 취소 계약 Named Test Fixture(CANCEL-CONTRACT-001 §11.4, Test Fixture Policy: Domain Factory → Named Fixture). 승인 상태는
 * [AuthorizationFixture]의 공식 전이로, 취소는 [CancelStart]로만 만든다. 반환 금액은 승인 금액(10,000 KRW 또는 10.50 USD)에서 나오며
 * 독립 random으로 고르지 않는다(A08 K-16).
 *
 * 완료·거절·늦은 성공은 [CancellationResolution]의 공식 전이로 만든다(T5 — T4 checkpoint의 내부 전이 상태 준비를 대체).
 *
 * 값은 모두 Demo다(운영 MID·기관·Capability 아님 — D-CANCEL-004, D-AUTH-009).
 */
object CancellationFixture {
    /** 승인 다음 날 12:00 KST. */
    val at: Instant = AuthorizationFixture.startedAt.plus(Duration.ofDays(1))

    fun cancellationId(n: Int = 1) = CancellationId("demo-cnl-%04d".format(n))

    fun cancelAttemptId(n: Int = 1) = CancelAttemptId("demo-catt-%04d".format(n))

    fun merchantCancelId(n: Int = 1) = MerchantCancelId("demo-cancel-%04d".format(n))

    fun cancelTransactionId(n: Int = 1) = PaymentTransactionId("demo-ctx-%04d".format(n))

    /** 전송 게이트가 취소 Attempt에 고정하는 Route 프로파일 버전(D-CANCEL1-004, Demo). */
    const val ROUTE_PROFILE_VERSION = "demo-route-v1"

    /** 원 경로(승인 Attempt의 기관·환경·기관 계정)로 보내는 취소 요청 ID(CC-09 `cr_` 모양 Demo 값). */
    fun cancelRequest(n: Int = 1, original: ProviderRequestRef = AuthorizationFixture.providerRequest) =
        original.copy(operation = "CANCEL", requestId = "cr_demo%04d".format(n))

    /** Demo Capability: 일시불은 신용·체크 부분취소 지원, 할부는 신용만. */
    val capability = CancelCapability("demo-cancel-cap-v1", partialCancelLumpSum = setOf(CardType.CREDIT, CardType.CHECK), partialCancelInstallment = setOf(CardType.CREDIT))

    /** Demo Capability: 할부 부분취소 미지원. */
    val noInstallmentPartial = capability.copy(version = "demo-cancel-cap-no-inst", partialCancelInstallment = emptySet())

    fun merchant(n: Int = 1): CancelInitiator = CancelInitiator.Merchant(merchantCancelId(n))

    fun approvalId(n: Int = 1) = OperatorApprovalId("demo-approval-%04d".format(n))

    val correctionPurpose: OperatorCancelPurpose = OperatorCancelPurpose.IncidentOrReconCorrection("recon-case-demo-0001")

    /**
     * 운영자 보정 취소(D-CANCEL-005, CC-12, review m-3·m-4·m-C): 요청자 ≠ 승인자, 사유, 목적·근거 참조, 2인 승인 ID, 승인한 대상 fingerprint(Payment·Money·잔액
     * revision(없으면 0)·approvalId·요청자·승인자·목적 종류·근거 참조 — 사유 문구 제외). 기본값은 실행 시점과 같은 대상을 승인한 정상 입력이다.
     */
    fun operator(
        s: AuthorizationScenario,
        money: Money,
        balance: ReturnBalance?,
        n: Int = 1,
        requestedBy: OperatorId = OperatorId("demo-op-requester"),
        approvedBy: OperatorId = OperatorId("demo-op-approver"),
        reason: String = "D-AUTH-011 이중 승인 보정",
        purpose: OperatorCancelPurpose = correctionPurpose,
        approvedRevision: Long = balance?.revision ?: 0L,
    ): CancelInitiator.Operator = CancelInitiator.Operator(
        requestedBy = requestedBy,
        approvedBy = approvedBy,
        approvalId = approvalId(n),
        reason = reason,
        purpose = purpose,
        approvedTargetFingerprint = OperatorCancelTarget(s.payment.id, money, approvedRevision, approvalId(n), requestedBy, approvedBy, purpose).fingerprint(),
    )

    fun command(
        s: AuthorizationScenario,
        balance: ReturnBalance?,
        money: Money,
        n: Int = 1,
        initiator: CancelInitiator = merchant(n),
        capability: CancelCapabilityResolution = CancelCapabilityResolution.Resolved(this.capability),
        providerRequest: ProviderRequestRef = cancelRequest(n),
        payment: Payment = s.payment,
        authorization: PaymentAuthorization? = s.authorization,
    ) = CancelStartCommand(
        control = s.control,
        payment = payment,
        authorization = authorization,
        balance = balance,
        money = money,
        initiator = initiator,
        capability = capability,
        cancellationId = cancellationId(n),
        attemptId = cancelAttemptId(n),
        providerRequest = providerRequest,
        at = at,
    )

    /** 취소 시작(TX1) 직후: 취소 OPEN·Attempt PREPARED·반환 예약 RESERVED. */
    fun started(s: AuthorizationScenario, balance: ReturnBalance?, money: Money, n: Int = 1, initiator: CancelInitiator = merchant(n)): CancelScenario {
        val result = CancelStart.start(command(s, balance, money, n, initiator))
        check(result is CancelStartResult.Started) { "fixture cancel start must succeed: $result" }
        return CancelScenario(s, result.balance, result.cancellation)
    }

    /** 기관이 확정한 취소 조건(요청과 같은 금액·원 승인 거래 참조). */
    fun confirmedTerms(money: Money, n: Int = 1, original: ApprovedTerms = AuthorizationFixture.approvedTerms()) =
        CancelConfirmedTerms(money, original.providerTransactionId, "PTX-SIM-C%03d".format(n), "C%04d".format(n), LocalDate.of(2026, 10, 16))

    /**
     * 같은 기관 거래 식별자(PTX)인데 조건이 다른 확정 취소 조건(D-CANCEL1-007 상충 의도 Fixture): [base]의 PTX를 그대로 두고 금액이나 원 승인 PTX만 바꾼다. 같은 결정이 아니다(새 사건).
     */
    fun samePtxDifferentTerms(base: CancelConfirmedTerms, money: Money = base.money, originalProviderTransactionId: String = base.originalProviderTransactionId) =
        base.copy(money = money, originalProviderTransactionId = originalProviderTransactionId).also {
            check(it != base && it.providerTransactionId == base.providerTransactionId) { "fixture must keep the PTX and change the terms" }
        }

    fun cancelObservation(
        c: CancelScenario,
        outcome: InstitutionOutcome,
        source: ObservationSource = ObservationSource.RESPONSE,
        resultId: String = "sim-cancel-result-1",
        terms: CancelConfirmedTerms? = if (outcome == InstitutionOutcome.APPROVED) confirmedTerms(c.cancellation.money) else null,
        observedAt: Instant = at.plusSeconds(2),
        evidenceHash: String? = "sha256:demo-cancel",
    ) = CancelObservation(c.attemptId, source, sourceResultId = resultId, outcome = outcome, confirmed = terms, evidenceHash = evidenceHash, observedAt = observedAt)

    /** 기관 취소 결과 반영(TX2·회복 공통 domain 연산). */
    fun applyCancel(c: CancelScenario, observation: CancelObservation, n: Int = 1): CancelResolutionOutcome =
        CancellationResolution.applyObservation(c.auth.control, c.auth.payment, c.cancellation, c.balance, observation, cancelTransactionId(n), at.plusSeconds(3))

    /** 완료된 취소: 전송 게이트 통과 → 기관 확정 성공 → COMPLETED(CANCEL 거래) + 예약 CONSUMED. */
    fun completed(c: CancelScenario, n: Int = 1): CancelScenario {
        c.cancellation.beginDispatch(c.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION)
        val out = applyCancel(c, cancelObservation(c, InstitutionOutcome.APPROVED), n)
        check(out is CancelResolutionOutcome.Completed) { "fixture cancel must complete: $out" }
        return c
    }

    /** 확정 거절된 취소: 전송 게이트 통과 → 기관 확정 거절 → FAILED + 예약 RELEASED. */
    fun declined(c: CancelScenario): CancelScenario {
        c.cancellation.beginDispatch(c.attemptId, CancellationFixture.ROUTE_PROFILE_VERSION)
        val out = applyCancel(c, cancelObservation(c, InstitutionOutcome.DECLINED))
        check(out is CancelResolutionOutcome.Failed) { "fixture cancel must be declined: $out" }
        return c
    }

    /** 승인된 10,000 KRW에서 [amount]를 부분취소 완료한 상태. */
    fun partiallyCancelled(amount: Money): CancelScenario = completed(started(AuthorizationFixture.authorized(), null, amount))

    /** 반환 진행 중(예약 RESERVED)인 승인. */
    fun paymentWithReservedReturn(amount: Money): CancelScenario = started(AuthorizationFixture.authorized(), null, amount)

    /**
     * 늦은 취소 성공(D-CANCEL-006, [CancellationResolution] 경로): [declinedAmount] 취소가 확정 거절(RELEASED)된 뒤 [thenCompleted](있으면)가 완료되고,
     * 그 다음 첫 취소의 늦은 성공이 와서 FORCED 재기록 + 이 Payment에 CANCEL_RESULT_CONFLICT hold. 반환 시나리오의 [CancelScenario.cancellation]은 첫 취소다.
     */
    fun lateCancelSuccessAfterDecline(declinedAmount: Money, thenCompleted: Money? = null): CancelScenario {
        val first = declined(started(AuthorizationFixture.authorized(), null, declinedAmount))
        if (thenCompleted != null) completed(started(first.auth, first.balance, thenCompleted, n = 2), n = 2)
        val out = applyCancel(first, cancelObservation(first, InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
        check(out is CancelResolutionOutcome.Incident && out.holdOpened) { "fixture late success must open a hold: $out" }
        return first
    }

    /** review H-1 재현: 10,000 전액 취소 거절 → 새 10,000 취소 완료 → 첫 취소 늦은 성공(FORCED 10,000, 초과 10,000). */
    fun paymentWithForcedReturnAfterLateCancelSuccess(): CancelScenario = lateCancelSuccessAfterDecline(10_000L.won(), 10_000L.won())

    /**
     * 같은 주문의 2번째 generation 승인(Payment demo-pay-0002·Authorization demo-auth-0002): 1번 시도가 기관 확정 거절된 뒤 새 Payment가 승인됐다.
     * [lateApprovalOnFirst]이면 그 뒤 1번 시도에 늦은 승인이 와서 1번 Payment에 hold(D-AUTH-011 이중 승인 상황). 반환 값은 2번 승인 시나리오다.
     */
    fun authorizedSecondGeneration(lateApprovalOnFirst: Boolean = false): Pair<AuthorizationScenario, AuthorizationScenario> {
        val first = AuthorizationFixture.declined()
        val second = PaymentFixture.requested(n = 2, slotGeneration = 2)
        first.control.occupy(second, first.payment)
        val gen2 = AuthorizationFixture.startedOn(OrderPaymentScenario(first.control, second, listOf(first.payment, second)), 2, first.usage)
        gen2.authorization.beginDispatch(gen2.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
        if (lateApprovalOnFirst) {
            AuthorizationFixture.apply(first, AuthorizationFixture.observation(InstitutionOutcome.APPROVED, source = ObservationSource.INQUIRY, resultId = "inq-late"))
        }
        AuthorizationFixture.apply(
            gen2, AuthorizationFixture.observation(InstitutionOutcome.APPROVED, attempt = gen2.attemptId), newTransactionId = PaymentTransactionId("demo-ptx-0002"),
        )
        return first to gen2
    }

    /**
     * D-CANCEL-010·011: 이중 승인의 늦게 확정된 P1. gen1(P1)이 확정 거절된 뒤 gen2(P2)가 승인되고, P1에 늦은 승인이 와서 hold(SUCCESS_AFTER_TERMINATION)가
     * 걸린 상태에서 RECON이 P1의 확정 승인을 거래로 기록했다(hold 유지). P1 Payment는 FAILED 그대로, Authorization만 늦은 AuthorizationTransaction을 얻는다.
     * RECON의 기록 Use Case는 아직 없어 모듈 internal 전이 `PaymentAuthorization.recordLateApprovalByReconciliation`(RECON-CONTRACT-001 인계, 명세 §14.1)으로
     * 만든다. 반환 값은 (P1 시나리오, P2 시나리오).
     */
    fun lateConfirmedFirstGeneration(): Pair<AuthorizationScenario, AuthorizationScenario> {
        val (first, gen2) = authorizedSecondGeneration(lateApprovalOnFirst = true)
        val late = first.authorization.observations.single { it.sourceResultId == "inq-late" }
        first.authorization.recordLateApprovalByReconciliation(
            AuthorizationTransaction(
                id = PaymentTransactionId("demo-ptx-late-0001"),
                authorizationId = first.authorization.id,
                paymentId = first.payment.id,
                terms = checkNotNull(late.approved),
                sourceObservation = late.key,
                confirmedAt = at,
            ),
        )
        return first to gen2
    }

    /** 일반 할부 3개월 신용 승인(Demo — 최소금액 없는 할부 정책). */
    fun authorizedInstallment(): AuthorizationScenario {
        val plan = PaymentPlan.Installment(3)
        val s = AuthorizationFixture.started(plan, CardType.CREDIT, AuthorizationFixture.lowMinimumPlanPolicy)
        s.authorization.beginDispatch(s.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
        AuthorizationFixture.apply(s, AuthorizationFixture.observation(InstitutionOutcome.APPROVED, terms = AuthorizationFixture.approvedTerms(plan = plan)))
        return s
    }

    /** USD 10.50(1050 cents) 일시불 신용 승인(D-CP-002 초기 검증 통화). 공식 factory·AuthorizationStart·Resolution으로만 만든다. */
    fun authorizedUsd(): AuthorizationScenario {
        val money = 1_050L.usdCents()
        val intent = OrderIntent(OrderFixture.demoSite, money)
        val payment = Payment.request(OrderFixture.paymentId(1), OrderFixture.demoOrder, intent.fingerprint(), 1, PaymentFixture.demoWindow)
        val control = OrderPaymentControl.open(payment)
        val f = AuthorizationFixture
        val session = AuthenticationSession.create(
            f.sessionId, payment, f.binding(payment, money = money), f.correlation(), f.startedAt.plus(f.sessionTtl), f.startedAt.minusSeconds(60), emptyList(),
        ).also {
            it.start(AuthorizationFixture.PROVIDER_TRANSACTION_REF)
            it.verify(f.evidence(it.binding), f.resultRefDigest(), f.authenticationRef, f.startedAt.minusSeconds(30))
        }
        val usage = DailyLimitUsage.open(LimitUsageKey(Environment.TEST, OrderFixture.demoMid, USD, LimitBusinessDate.of(f.startedAt)))
        val ready = StartReady(control, payment, session, usage)
        val limit = LimitPolicyResolution.Resolved(f.limitPolicy().copy(version = "demo-limit-usd", currency = USD))
        val plans = f.lowMinimumPlanPolicy.copy(version = "demo-plan-usd", currency = USD)
        val result = AuthorizationStart.start(f.command(ready, limit = limit, plans = plans, intent = intent))
        check(result is AuthorizationStartResult.Started) { "fixture start must succeed: $result" }
        val s = AuthorizationScenario(control, payment, session, usage, result.authorization)
        s.authorization.beginDispatch(s.attemptId, AuthorizationFixture.ROUTE_PROFILE_VERSION)
        f.apply(s, f.observation(InstitutionOutcome.APPROVED, terms = f.approvedTerms(money = money)), policy = null)
        return s
    }
}

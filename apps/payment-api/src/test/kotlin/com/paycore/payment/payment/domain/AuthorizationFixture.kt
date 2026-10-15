package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.MoneyFixture.KRW
import com.paycore.payment.payment.domain.MoneyFixture.won
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** 승인 시작 직전 상태(같은 TX1에서 application이 잠가 읽은 Aggregate 묶음). */
class StartReady(
    val control: OrderPaymentControl,
    val payment: Payment,
    val session: AuthenticationSession,
    val usage: DailyLimitUsage,
)

/** 승인을 시작한 주문(control·Payment·세션·한도 사용량·Authorization이 함께 정해진다). */
class AuthorizationScenario(
    val control: OrderPaymentControl,
    val payment: Payment,
    val session: AuthenticationSession,
    val usage: DailyLimitUsage,
    val authorization: PaymentAuthorization,
) {
    val attemptId: AttemptId
        get() = authorization.attempts.single().id
}

/**
 * 승인 계약 Named Test Fixture(Test Fixture Policy: Domain Factory → Named Fixture). 모든 상태는 공식 factory(`AuthenticationSession.create`,
 * `AuthorizationStart.start`, `DailyLimitUsage.open`)와 공개 전이로만 만든다. 금액·Plan·cardType·한도는 서로 독립적으로 고르지 않는다:
 * 승인 금액은 Payment 고정 입력(10,000 KRW), 세션 바인딩·한도 점유는 그 값에서 나온다.
 *
 * 값은 모두 Demo다(운영 MID·카드사·한도·할부 개월 아님 — D-AUTH-001·D-AUTH-009).
 */
object AuthorizationFixture {
    /** 2026-10-15 12:00 KST(UTC 03:00) — 일 한도 업무일 2026-10-15. */
    val startedAt: Instant = Instant.parse("2026-10-15T03:00:00Z")

    val sessionTtl: Duration = Duration.ofMinutes(10)

    val authorizationId = AuthorizationId("demo-auth-0001")
    val attemptId = AttemptId("demo-att-0001")
    val sessionId = AuthenticationSessionId("demo-authn-0001")
    val transactionId = PaymentTransactionId("demo-ptx-0001")

    /** Payment 고정 입력(OrderFixture.originalIntent와 같은 Site·금액). */
    val intent = OrderIntent(OrderFixture.demoSite, 10_000L.won())

    val providerRequest = ProviderRequestRef("SIM-VAN-A", Environment.TEST, "sim-acct-01", "AUTHORIZE", "ar_demo0001")

    val admission = AuthorizationAdmission("es1:demo-merchant:7", "demo-policy-assignment-v1", "SIM-VAN-A", "sim-van/1", startedAt)

    /** Demo 할부 정책: 2·3·6·12개월, 최소 50,000원. */
    val planPolicy = PaymentPlanPolicy("demo-plan-v1", KRW, setOf(2, 3, 6, 12), 50_000L.won())

    /** 일시불 승인용 Demo 할부 정책(10,000원 할부도 허용해 최소금액 조건과 체크카드 규칙을 분리해 본다). */
    val lowMinimumPlanPolicy = PaymentPlanPolicy("demo-plan-v1-low", KRW, setOf(2, 3, 6, 12), null)

    fun limitPolicy(
        perTransaction: LimitAmount = LimitAmount.Limited(1_000_000),
        daily: LimitAmount = LimitAmount.Limited(5_000_000),
    ) = TransactionLimitPolicy("demo-limit-v1", OrderFixture.demoMid, KRW, perTransaction, daily)

    fun usageKey(at: Instant = startedAt) = LimitUsageKey(Environment.TEST, OrderFixture.demoMid, KRW, LimitBusinessDate.of(at))

    fun binding(payment: Payment, plan: PaymentPlan = PaymentPlan.LumpSum, money: Money = intent.money) =
        AuthenticationBinding(payment.id, payment.orderKey, money, plan, "DEMO-CARD-CO")

    fun evidence(binding: AuthenticationBinding, cardType: CardType = CardType.CREDIT, ref: String = "demo-authn-evidence-1") =
        VerifiedAuthentication(ref, binding, cardType, "DEMO-ISSUER", "DEMO-ACQUIRER", "DEMO-AUTHN-PROVIDER")

    /** 결제창에서 인증 중인 세션(IN_PROGRESS). */
    fun inProgressSession(payment: Payment, plan: PaymentPlan = PaymentPlan.LumpSum, id: AuthenticationSessionId = sessionId): AuthenticationSession =
        AuthenticationSession.create(id, payment, binding(payment, plan), startedAt.plus(sessionTtl), startedAt.minusSeconds(60), emptyList())
            .also { it.start() }

    /** 서버 검증을 마친 미귀속 세션(VERIFIED). */
    fun verifiedSession(payment: Payment, plan: PaymentPlan = PaymentPlan.LumpSum, cardType: CardType = CardType.CREDIT): AuthenticationSession =
        inProgressSession(payment, plan).also { s ->
            s.verify(evidence(s.binding, cardType), startedAt.minusSeconds(30)) shouldBeOutcome VerificationOutcome.VERIFIED
        }

    /** REQUESTED Payment + VERIFIED 세션 + 빈 일 한도 사용량. */
    fun readyToStart(plan: PaymentPlan = PaymentPlan.LumpSum, cardType: CardType = CardType.CREDIT): StartReady {
        val order = OrderPaymentControlFixture.openedWithRequestedPayment()
        return StartReady(order.control, order.current, verifiedSession(order.current, plan, cardType), DailyLimitUsage.open(usageKey()))
    }

    fun command(
        ready: StartReady,
        limit: LimitPolicyResolution = LimitPolicyResolution.Resolved(limitPolicy()),
        plans: PaymentPlanPolicy = planPolicy,
        at: Instant = startedAt,
        intent: OrderIntent = this.intent,
    ) = AuthorizationStartCommand(
        control = ready.control,
        payment = ready.payment,
        intent = intent,
        session = ready.session,
        admission = admission,
        planPolicy = plans,
        limitPolicy = limit,
        usage = ready.usage,
        authorizationId = authorizationId,
        attemptId = attemptId,
        providerRequest = providerRequest,
        at = at,
    )

    /** 승인 시작(TX1) 직후: Payment AUTHORIZING, Authorization OPEN, Attempt PREPARED. */
    fun started(plan: PaymentPlan = PaymentPlan.LumpSum, cardType: CardType = CardType.CREDIT, plans: PaymentPlanPolicy = planPolicy): AuthorizationScenario {
        val ready = readyToStart(plan, cardType)
        val result = AuthorizationStart.start(command(ready, plans = plans))
        check(result is AuthorizationStartResult.Started) { "fixture start must succeed: $result" }
        return AuthorizationScenario(ready.control, ready.payment, ready.session, ready.usage, result.authorization)
    }

    /** 전송 게이트 통과(TXd) 뒤: Attempt DISPATCHING. */
    fun dispatching(): AuthorizationScenario = started().also { it.authorization.beginDispatch(it.attemptId) }

    /** 전송 뒤 read timeout: Payment·Authorization UNKNOWN, Attempt OUTCOME_UNKNOWN. */
    fun unknownAfterTimeout(): AuthorizationScenario = dispatching().also {
        AuthorizationResolution.markOutcomeUnknown(it.payment, it.authorization, it.attemptId, UnknownCause.TIMEOUT)
    }

    /** 요청과 같은 조건의 기관 승인 조건. */
    fun approvedTerms(money: Money = intent.money, plan: PaymentPlan = PaymentPlan.LumpSum, cardType: CardType = CardType.CREDIT) =
        ApprovedTerms(money, plan, cardType, "A0001", "PTX-SIM-0001", LocalDate.of(2026, 10, 15))

    fun observation(
        outcome: InstitutionOutcome,
        source: ObservationSource = ObservationSource.RESPONSE,
        resultId: String = "sim-result-1",
        terms: ApprovedTerms? = if (outcome == InstitutionOutcome.APPROVED) approvedTerms() else null,
        attempt: AttemptId = attemptId,
        observedAt: Instant = startedAt.plusSeconds(2),
        evidenceHash: String? = "sha256:demo",
    ) = InstitutionObservation(attempt, source, resultId, outcome, terms, evidenceHash, observedAt)

    fun apply(
        s: AuthorizationScenario,
        observation: InstitutionObservation,
        policy: TransactionLimitPolicy? = limitPolicy(),
        newTransactionId: PaymentTransactionId = transactionId,
    ): ResolutionOutcome =
        AuthorizationResolution.applyObservation(s.control, s.payment, s.authorization, s.usage, observation, newTransactionId, policy, startedAt.plusSeconds(3))

    /**
     * 주어진 주문의 현재 REQUESTED Payment([order].current)로 승인을 시작한다. 같은 주문의 이전 generation과 다른 ID·같은 업무일 사용량을
     * 쓸 때 [n]과 [usage]를 넘긴다(D-AUTH-011 이중 승인 시나리오).
     */
    fun startedOn(order: OrderPaymentScenario, n: Int, usage: DailyLimitUsage): AuthorizationScenario {
        val session = AuthenticationSession.create(
            AuthenticationSessionId("demo-authn-%04d".format(n)), order.current, binding(order.current), startedAt.plus(sessionTtl), startedAt.minusSeconds(60), emptyList(),
        ).also { it.start(); it.verify(evidence(it.binding), startedAt.minusSeconds(30)) }
        val ready = StartReady(order.control, order.current, session, usage)
        val result = AuthorizationStart.start(
            command(ready).copy(
                authorizationId = AuthorizationId("demo-auth-%04d".format(n)),
                attemptId = AttemptId("demo-att-%04d".format(n)),
                providerRequest = providerRequest.copy(requestId = "ar_demo%04d".format(n)),
            ),
        )
        check(result is AuthorizationStartResult.Started) { "fixture start must succeed: $result" }
        return AuthorizationScenario(order.control, order.current, session, usage, result.authorization)
    }

    /** 승인 확정된 주문(거래 1건). */
    fun authorized(): AuthorizationScenario = dispatching().also { apply(it, observation(InstitutionOutcome.APPROVED)) }

    /** 기관 확정 거절로 종료된 주문. */
    fun declined(): AuthorizationScenario = dispatching().also { apply(it, observation(InstitutionOutcome.DECLINED)) }

    private infix fun VerificationOutcome.shouldBeOutcome(expected: VerificationOutcome) {
        check(this == expected) { "fixture verification must be $expected but was $this" }
    }
}

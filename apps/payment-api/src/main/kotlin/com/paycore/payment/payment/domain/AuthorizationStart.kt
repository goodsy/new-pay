package com.paycore.payment.payment.domain

import java.time.Instant

/**
 * 승인 시작 입력(AUTH-CONTRACT-001 §4.2). application이 TX1에서 잠금 순서대로 읽어 넘긴다: control → Payment → 인증 세션 → 한도 사용량
 * (§4.1). [intent]는 Payment 생성 시 불변 값(Site·Money)이며 domain이 Payment 고정 입력 fingerprint와 대조한다. [admission]은 허용 판정
 * Port가 Admitted로 돌려준 근거, [limitPolicy]는 적용 한도 정책 해석 결과다(없음·모호 → 거절, D-AUTH-006).
 */
data class AuthorizationStartCommand(
    val control: OrderPaymentControl,
    val payment: Payment,
    val intent: OrderIntent,
    val session: AuthenticationSession,
    val admission: AuthorizationAdmission,
    val planPolicy: PaymentPlanPolicy,
    val limitPolicy: LimitPolicyResolution,
    val usage: DailyLimitUsage,
    val authorizationId: AuthorizationId,
    val attemptId: AttemptId,
    val providerRequest: ProviderRequestRef,
    val at: Instant,
)

/** 전송 전 업무 거절(D-AUTH-005: Payment REQUESTED 유지, 세션 미귀속, 키 결과로 저장·재생은 application). */
sealed interface AuthorizationStartRejection {
    data class AuthenticationNotVerified(val reason: BindingRejection) : AuthorizationStartRejection

    data class PlanNotAllowed(val reason: PlanRejection) : AuthorizationStartRejection

    data class LimitExceeded(val reason: LimitRejection) : AuthorizationStartRejection

    /** 적용 한도 정책이 없거나 모호하다(fail-closed, D-AUTH-006). */
    data class LimitPolicyUnavailable(val resolution: LimitPolicyResolution) : AuthorizationStartRejection
}

sealed interface AuthorizationStartResult {
    data class Started(val authorization: PaymentAuthorization) : AuthorizationStartResult

    data class Rejected(val reason: AuthorizationStartRejection) : AuthorizationStartResult
}

/**
 * 승인 시작 단일 domain 연산(L-2·L-3, AC 7). 모든 검사를 먼저 하고 통과할 때만 함께 바꾼다:
 * control 슬롯(`OrderPaymentControl.startAuthorization` 경유 Payment REQUESTED → AUTHORIZING) + [PaymentAuthorization] OPEN(Attempt PREPARED)
 * + 인증 세션 귀속 + 일 한도 점유. 하나라도 실패하면 어느 Aggregate도 바뀌지 않는다.
 *
 * 검사 순서: 슬롯(stale·REQUESTED 아님·incident hold·과거 승인 — 기존 예외) → 입력 정합성(결함은 [IllegalArgumentException]) →
 * 인증 근거 → 결제 방식 → 한도 정책 → 한도. 업무 거절은 [AuthorizationStartResult.Rejected]로 돌려준다.
 * main 코드에서 control.startAuthorization·PaymentAuthorization.open·세션 bindTo·한도 reserve는 이 객체만 부른다(ArchUnit).
 */
object AuthorizationStart {

    /**
     * 읽기 전용 사전 검사(AUTH-001 명세 §4.11-7, AU-05). application이 승인 시작 허용 판정 Port(인증 증거의 매입사 코드가 필요)를 부르기 전에 거절 순서를 맞춘다:
     * 슬롯 위반은 [start]와 같은 예외, 세션 없음·귀속 불가는 [AuthorizationStartRejection.AuthenticationNotVerified]. 어떤 Aggregate도 바꾸지 않으며 [start]는
     * 모든 검사를 다시 한다(L-2 단일 연산 유지).
     */
    fun preflight(control: OrderPaymentControl, payment: Payment, session: AuthenticationSession?, money: Money, at: Instant): AuthorizationStartRejection? {
        control.checkAuthorizationStart(payment)
        if (session == null) return AuthorizationStartRejection.AuthenticationNotVerified(BindingRejection.NOT_VERIFIED)
        return session.bindingRejection(payment.id, money, at)?.let { AuthorizationStartRejection.AuthenticationNotVerified(it) }
    }

    fun start(command: AuthorizationStartCommand): AuthorizationStartResult = with(command) {
        control.checkAuthorizationStart(payment)
        require(intent.fingerprint() == payment.intent) { "intent must be the payment's fixed intent" }
        val money = intent.money
        val expectedKey = LimitUsageKey(payment.orderKey.environment, payment.orderKey.merchantAccountId, money.currency, LimitBusinessDate.of(at))
        require(usage.key == expectedKey) { "limit usage must be the one for this order scope and business date" }
        require(providerRequest.environment == payment.orderKey.environment) { "provider request environment must match the order" }
        require(usage.reservationOf(authorizationId) == null) { "authorization id is already reserved" }

        session.bindingRejection(payment.id, money, at)?.let {
            return AuthorizationStartResult.Rejected(AuthorizationStartRejection.AuthenticationNotVerified(it))
        }
        val verified = checkNotNull(session.verified) { "a bindable session is VERIFIED" }
        PaymentPlanRule.check(session.binding.plan, verified.cardType, money, planPolicy)?.let {
            return AuthorizationStartResult.Rejected(AuthorizationStartRejection.PlanNotAllowed(it))
        }
        val policy = when (limitPolicy) {
            is LimitPolicyResolution.Resolved -> limitPolicy.policy
            LimitPolicyResolution.Missing, LimitPolicyResolution.Ambiguous ->
                return AuthorizationStartResult.Rejected(AuthorizationStartRejection.LimitPolicyUnavailable(limitPolicy))
        }
        usage.rejectionFor(money, policy)?.let {
            return AuthorizationStartResult.Rejected(AuthorizationStartRejection.LimitExceeded(it))
        }

        control.startAuthorization(payment)
        val authorization = PaymentAuthorization.open(
            id = authorizationId,
            payment = payment,
            intent = intent,
            plan = session.binding.plan,
            authenticatedCardType = verified.cardType,
            authenticationSessionId = session.id,
            admission = admission,
            planPolicyVersion = planPolicy.version,
            limitPolicyVersion = policy.version,
            limitKey = usage.key,
            attemptId = attemptId,
            providerRequest = providerRequest,
            openedAt = at,
        )
        session.bindTo(authorization.id, payment.id, money, at)
        usage.reserve(authorization.id, money, policy)
        AuthorizationStartResult.Started(authorization)
    }
}

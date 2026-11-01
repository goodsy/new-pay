package com.paycore.payment.payment.domain

import com.paycore.payment.payment.domain.AuthorizationFixture.providerValidUntil
import com.paycore.payment.payment.domain.AuthorizationFixture.startedAt
import java.time.Instant

/**
 * 인증기관 조회 결과 Named Test Fixture(AUTHN-001 명세 §7.3, Test Fixture Policy). 정상 결과는 세션의 바인딩·상관 값에서 만든다(독립 난수 없음).
 * 일부러 틀린 결과는 함수 이름에 의도를 적는다(Invalid Fixture). 값은 Demo(Simulator 가상 카드 모양 — 카드번호 없음).
 */
object ProviderResultFixture {
    val authenticatedAt: Instant = startedAt.minusSeconds(40)

    fun echoOf(binding: AuthenticationBinding) = ProviderBindingEcho(
        paymentId = binding.paymentId,
        environment = binding.orderKey.environment,
        merchantAccountId = binding.orderKey.merchantAccountId,
        money = binding.money,
        plan = binding.plan,
        cardCompany = binding.cardCompany,
    )

    fun details(session: AuthenticationSession, cardType: String = "CREDIT", issuer: String = session.binding.cardCompany) =
        AuthenticatedDetails(cardType, issuer, "DEMO-ACQUIRER", "DEMO-AUTHN-PROVIDER", "demo-authn-evidence-1", authenticatedAt)

    fun authenticatedMatching(session: AuthenticationSession, cardType: String = "CREDIT", validUntil: Instant = providerValidUntil) =
        ProviderAuthenticationResult(
            providerTransactionRef = checkNotNull(session.providerTransactionRef) { "fixture needs a started session" },
            outcome = ProviderOutcome.AUTHENTICATED,
            stateDigest = session.correlation.stateDigest,
            binding = echoOf(session.binding),
            authenticated = details(session, cardType),
            validUntil = validUntil,
        )

    private fun withOutcome(session: AuthenticationSession, outcome: ProviderOutcome) =
        authenticatedMatching(session).copy(outcome = outcome, authenticated = null)

    fun failed(session: AuthenticationSession) = withOutcome(session, ProviderOutcome.FAILED)

    fun cancelled(session: AuthenticationSession) = withOutcome(session, ProviderOutcome.CANCELLED)

    fun expiredAtProvider(session: AuthenticationSession) = withOutcome(session, ProviderOutcome.EXPIRED)

    fun authenticatedWithDifferentAmount(session: AuthenticationSession) = authenticatedMatching(session).let {
        it.copy(binding = it.binding.copy(money = Money(session.binding.money.amountMinor + 1, session.binding.money.currency)))
    }

    fun authenticatedWithDifferentMid(session: AuthenticationSession) = authenticatedMatching(session).let {
        it.copy(binding = it.binding.copy(merchantAccountId = MerchantAccountId("demo-other-mid")))
    }

    fun authenticatedForAnotherTransaction(session: AuthenticationSession) = authenticatedMatching(session).copy(providerTransactionRef = "sat_other0001")

    fun authenticatedWithDifferentEchoedState(session: AuthenticationSession) =
        authenticatedMatching(session).copy(stateDigest = SecretDigest.of(SecretKind.AUTHENTICATION_STATE, "demo-state-other"))

    fun authenticatedByAnotherIssuer(session: AuthenticationSession) =
        authenticatedMatching(session).copy(authenticated = details(session, issuer = "SIMISSB"))

    fun authenticatedWithUnsupportedCardType(session: AuthenticationSession) = authenticatedMatching(session, cardType = "PREPAID")
}

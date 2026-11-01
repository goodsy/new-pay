package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.application.required.AuthenticationSessionAttributes
import com.paycore.payment.payment.application.required.CheckoutSessionRecord
import com.paycore.payment.payment.application.required.CheckoutSessionStatus
import com.paycore.payment.payment.application.required.CheckoutTokenRecord
import com.paycore.payment.payment.application.required.CheckoutTokenStatus
import com.paycore.payment.payment.domain.AuthenticationBinding
import com.paycore.payment.payment.domain.AuthenticationCorrelation
import com.paycore.payment.payment.domain.AuthenticationSession
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.AuthenticationSessionStart
import com.paycore.payment.payment.domain.CheckoutHandle
import com.paycore.payment.payment.domain.CheckoutSessionId
import com.paycore.payment.payment.domain.CheckoutTokenId
import com.paycore.payment.payment.domain.MerchantReturnUrl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.ReturnUrlParse
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import java.time.Instant

/**
 * 결제창·인증 세션 영속 시험 Named Fixture(AUTHN-001 S4, Test Fixture Policy — src/test 전용). 값은 Demo(host `shop.example.test`, 가상 카드사 코드).
 * 바인딩 값은 [PersistenceTestData]의 Payment 생성 값에서 나온다(독립 난수 없음). 식별자·비밀 원문만 시험마다 새로 만든다(공유 컨텍스트에서 섞이지 않게).
 */
object CheckoutPersistenceFixture {
    const val SITE_HOST = "shop.example.test"
    val issuedAt: Instant = PersistenceTestData.t0.plusSeconds(1)

    fun url(raw: String): MerchantReturnUrl = (MerchantReturnUrl.parse(raw) as ReturnUrlParse.Accepted).url

    private fun alnum(prefix: String) = unique(prefix).replace("-", "")

    fun newTokenId() = CheckoutTokenId("ctk_" + alnum("t"))

    fun newSessionId() = CheckoutSessionId("cks_" + alnum("s"))

    fun newHandle() = CheckoutHandle(alnum("h").padEnd(22, 'x').take(22))

    fun digest(kind: SecretKind) = SecretDigest.of(kind, unique("raw-secret"))

    fun token(payment: Payment, id: CheckoutTokenId = newTokenId(), status: CheckoutTokenStatus = CheckoutTokenStatus.ISSUED) = CheckoutTokenRecord(
        id = id,
        digest = digest(SecretKind.CHECKOUT_TOKEN),
        paymentId = payment.id,
        orderKey = payment.orderKey,
        merchantSiteId = PersistenceTestData.demoSite,
        money = PersistenceTestData.intent().money,
        successUrl = url("https://$SITE_HOST/pay/ok?x=1"),
        failUrl = url("https://$SITE_HOST/pay/fail"),
        siteHost = SITE_HOST,
        siteSnapshotVersion = "es1:demo-merchant:3",
        status = status,
        issuedAt = issuedAt,
        expiresAt = issuedAt.plusSeconds(300),
        consumedAt = null,
        closedAt = null,
        issuedByCredentialId = PersistenceTestData.demoActor,
        policyVersion = "checkout-demo-1",
    )

    fun session(token: CheckoutTokenRecord, id: CheckoutSessionId = newSessionId(), handle: CheckoutHandle = newHandle()) = CheckoutSessionRecord(
        id = id,
        handle = handle,
        digest = digest(SecretKind.CHECKOUT_SESSION),
        tokenId = token.id,
        paymentId = token.paymentId,
        successUrl = token.successUrl,
        failUrl = token.failUrl,
        siteHost = token.siteHost,
        status = CheckoutSessionStatus.ACTIVE,
        closeReason = null,
        createdAt = issuedAt.plusSeconds(10),
        expiresAt = issuedAt.plusSeconds(1200),
        closedAt = null,
        policyVersion = "checkout-demo-1",
    )

    fun attributes(checkoutSessionId: CheckoutSessionId) = AuthenticationSessionAttributes(checkoutSessionId, "sim-auth-demo", "plan-demo-1", "checkout-demo-1")

    fun binding(payment: Payment, plan: PaymentPlan = PaymentPlan.LumpSum) =
        AuthenticationBinding(payment.id, payment.orderKey, PersistenceTestData.intent().money, plan, "SIMISSA")

    /** 공식 경로(AuthenticationSessionStart)로 만든 CREATED 세션. */
    fun created(payment: Payment, earlier: List<AuthenticationSession> = emptyList(), at: Instant = issuedAt.plusSeconds(20)): AuthenticationSession =
        AuthenticationSessionStart.start(
            payment, earlier, AuthenticationSessionId("aus_" + alnum("a")), binding(payment),
            AuthenticationCorrelation(digest(SecretKind.AUTHENTICATION_STATE)), at.plusSeconds(600), at,
        ).session
}

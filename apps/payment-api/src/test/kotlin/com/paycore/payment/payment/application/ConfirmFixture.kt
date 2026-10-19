package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.CheckoutPersistenceFixture
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.adapter.web.ApiTestTimes
import com.paycore.payment.payment.application.provided.ConfirmPaymentCommand
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.required.AuthenticationSessionAttributes
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.AuthenticationBinding
import com.paycore.payment.payment.domain.AuthenticationCorrelation
import com.paycore.payment.payment.domain.AuthenticationRef
import com.paycore.payment.payment.domain.AuthenticationSessionId
import com.paycore.payment.payment.domain.AuthenticationSessionStart
import com.paycore.payment.payment.domain.CardType
import com.paycore.payment.payment.domain.CheckoutWindow
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.PaymentPlan
import com.paycore.payment.payment.domain.SecretDigest
import com.paycore.payment.payment.domain.SecretKind
import com.paycore.payment.payment.domain.VerificationOutcome
import com.paycore.payment.payment.domain.VerifiedAuthentication
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * confirm 시험 Named Fixture(AUTH-001 S5, src/test 전용). 저장된 REQUESTED Payment + 주문 control + 결제창 토큰·세션 + VERIFIED 인증 세션을 **공식 domain 전이를 저장하며**
 * 만든다(reflection·검증 우회 없음). 바인딩(금액·통화·Plan)은 Payment 고정 입력에서 나온다 — 독립 난수 없음. 시각은 API 시험 시계 [ApiTestTimes.T0] 기준(결제창 T0 + 30분,
 * 세션 유효 T0 + 10분, 기관 근거 유효 T0 + 20분 — Demo). MID·주문 ID·참조만 시험마다 새로 만든다.
 */
class ConfirmFixture(
    private val payments: PaymentRepository,
    private val controls: OrderPaymentControlRepository,
    private val sessions: AuthenticationSessionRepository,
    private val tokens: CheckoutTokenStore,
    private val checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    private val db: DbAccess,
) {
    private val tx = TransactionTemplate(transactionManager)

    data class Prepared(
        val order: OrderKey, val paymentId: PaymentId, val authenticationRef: AuthenticationRef, val money: Money, val plan: PaymentPlan,
        /** 결제창 handle·쿠키 비밀 원문(인증 시작 경합 C8용 — 시험 전용 값). */
        val handle: String, val cookieSecret: String,
    ) {
        fun command(
            key: String = newKey(),
            authenticationRef: String = this.authenticationRef.value,
            merchantOrderId: String = order.merchantOrderId.value,
            money: Money = this.money,
            paymentId: String = this.paymentId.value,
            mid: com.paycore.payment.payment.domain.MerchantAccountId = order.merchantAccountId,
            correlationId: String = "req-confirm-" + key,
        ) = ConfirmPaymentCommand(
            environment = order.environment,
            merchantAccountId = mid,
            idempotencyKey = IdempotencyKey(key),
            paymentId = paymentId,
            authenticationRef = authenticationRef,
            merchantOrderId = merchantOrderId,
            money = money,
            correlationId = correlationId.take(128),
        )
    }

    /**
     * @param verified false면 VERIFIED 전(IN_PROGRESS) 세션 — 승인 근거 없음.
     * @param windowEnd 결제창 절대 만료 시각(기본 T0 + 30분).
     */
    fun prepared(
        amount: Long = 10_000,
        currency: CurrencyCode = PersistenceTestData.KRW,
        plan: PaymentPlan = PaymentPlan.LumpSum,
        cardType: CardType = CardType.CREDIT,
        verified: Boolean = true,
        windowEnd: Instant? = null,
        providerValidUntil: Instant? = null,
        order: OrderKey = PersistenceTestData.newOrder(),
        /** 시각 기준(기본 T0) — 모든 준비 시각이 이 값에서 상대적으로 정해진다(업무일 경계 시험용). */
        base: Instant = T0,
    ): Prepared {
        val shift = Duration.between(T0, base)
        val windowEndAt = windowEnd ?: T0.plus(Duration.ofMinutes(30)).plus(shift)
        val validUntil = providerValidUntil ?: T0.plus(Duration.ofMinutes(20)).plus(shift)
        val intent = OrderIntent(PersistenceTestData.demoSite, Money(amount, currency))
        val payment = Payment.request(PaymentId(DbAccess.unique("pay")), order, intent.fingerprint(), 1, CheckoutWindow(windowEndAt, "checkout-validity-demo-1"))
        val ref = AuthenticationRef("aref_" + UUID.randomUUID().toString().replace("-", "").take(22))
        val cookieSecret = "cks-secret-" + UUID.randomUUID().toString().replace("-", "")
        val handle = CheckoutPersistenceFixture.newHandle()
        tx.executeWithoutResult {
            payments.insertNew(
                payment,
                PaymentCreationAttributes(PaymentKey(DbAccess.unique("pk")), intent.merchantSiteId, intent.money, PaymentMethod.CARD, "Demo order", PersistenceTestData.snapshot, (T0.minusSeconds(120)).plus(shift), ACTOR),
            )
            controls.openOrLock(OrderPaymentControl.open(payment), (T0.minusSeconds(120)).plus(shift))
            val token = CheckoutPersistenceFixture.token(payment).copy(money = intent.money, issuedAt = (T0.minusSeconds(110)).plus(shift), expiresAt = (T0.plusSeconds(190)).plus(shift))
            tokens.insert(token)
            val cs = CheckoutPersistenceFixture.session(token, handle = handle)
                .copy(digest = SecretDigest.of(SecretKind.CHECKOUT_SESSION, cookieSecret), createdAt = (T0.minusSeconds(100)).plus(shift), expiresAt = (T0.plusSeconds(1100)).plus(shift))
            checkoutSessions.insert(cs)
            val binding = AuthenticationBinding(payment.id, payment.orderKey, intent.money, plan, "SIMISS-A")
            val session = AuthenticationSessionStart.start(
                payment, emptyList(), AuthenticationSessionId("aus_" + UUID.randomUUID().toString().replace("-", "").take(24)), binding,
                AuthenticationCorrelation(SecretDigest.of(SecretKind.AUTHENTICATION_STATE, DbAccess.unique("st"))), (T0.plus(Duration.ofMinutes(10))).plus(shift), (T0.minusSeconds(90)).plus(shift),
            ).session
            var version = sessions.insertNew(session, AuthenticationSessionAttributes(cs.id, "sim-auth-demo", "plan-demo-1", "checkout-demo-1"))
            session.start("sat_" + UUID.randomUUID().toString().replace("-", "").take(22))
            version = sessions.update(session, version)
            if (verified) {
                val evidence = VerifiedAuthentication("sim-ev-" + UUID.randomUUID(), binding, cardType, "SIMISS-A", "SIMACQ-A", "SIMAUTH-1", validUntil)
                check(session.verify(evidence, SecretDigest.of(SecretKind.RESULT_REFERENCE, DbAccess.unique("rr")), ref, (T0.minusSeconds(60)).plus(shift)) == VerificationOutcome.VERIFIED)
                sessions.update(session, version)
            }
        }
        return Prepared(order, payment.id, ref, intent.money, plan, handle.value, cookieSecret)
    }

    fun scalar(sql: String, vararg args: Any?): Any? = db.withApp { c -> with(db) { c.scalar(sql, *args) } }

    fun count(sql: String, vararg args: Any?): Long = db.count(sql, *args)

    /**
     * 시험 MID의 권리 투영 head(revision 0 — 아직 Snapshot 없음)와 MID 소유 행을 root로 넣는다(C26·n-8: head 잠금이 실제 행에 걸리게). 권리 판정은 시험 Admission 대역이 한다.
     * @return head의 merchant_id
     */
    fun entitlementHead(mid: MerchantAccountId): String {
        val merchantId = "mch-" + mid.value.takeLast(24)
        db.root().use { c ->
            c.prepareStatement("INSERT INTO entitlement_head (merchant_id, revision, version) VALUES (?, 0, 0)").use { it.setString(1, merchantId); it.executeUpdate() }
            c.prepareStatement("INSERT INTO entitlement_mid_owner (merchant_account_id, merchant_id, recorded_at) VALUES (?, ?, UTC_TIMESTAMP(3))").use {
                it.setString(1, mid.value)
                it.setString(2, merchantId)
                it.executeUpdate()
            }
        }
        return merchantId
    }

    fun paymentStatus(p: Prepared): String = scalar("SELECT status FROM payment WHERE payment_id = ?", p.paymentId.value) as String

    fun authorizationRows(p: Prepared): Long = count("SELECT COUNT(*) FROM payment_authorization WHERE payment_id = ?", p.paymentId.value)

    fun idempotencyRows(p: Prepared): List<Map<String, Any?>> = db.withApp { c ->
        with(db) { c.rows("SELECT result_kind, result_code, result_payment_id, result_authorization_id, result_detail FROM idempotency_request WHERE merchant_account_id = ? AND operation = 'ConfirmPayment' ORDER BY id", p.order.merchantAccountId.value) }
    }

    companion object {
        val T0: Instant = ApiTestTimes.T0
        val ACTOR = CredentialKeyId("demoActor0001")

        fun newKey(): String = "ck-" + UUID.randomUUID().toString().replace("-", "").take(24)
    }
}

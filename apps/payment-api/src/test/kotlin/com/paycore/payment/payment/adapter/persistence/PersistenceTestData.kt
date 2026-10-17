package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.adapter.persistence.DbAccess.Companion.unique
import com.paycore.payment.payment.application.provided.CredentialKeyId
import com.paycore.payment.payment.application.provided.EligibilitySnapshotVersion
import com.paycore.payment.payment.application.required.PaymentCreationAttributes
import com.paycore.payment.payment.domain.CheckoutWindow
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderIntent
import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentKey
import com.paycore.payment.payment.domain.PaymentMethod
import java.time.Instant

/**
 * persistence/application DB 시험의 Named Test Fixture. Demo 값(운영 MID·Site·금액 아님). 시험마다 [unique] 접미사로 MID·주문을 새로
 * 만들어 같은 컨텍스트를 공유하는 다른 시험의 행과 섞이지 않게 한다. Payment는 domain 공식 생성(`Payment.request`)으로만 만든다.
 */
object PersistenceTestData {
    val demoSite = MerchantSiteId("demo-site-001")
    val KRW = CurrencyCode("KRW")
    val USD = CurrencyCode("USD")
    val snapshot = EligibilitySnapshotVersion("demo-snapshot-1")

    /** 고정 시험 시각(ms 단위, UTC). */
    val t0: Instant = Instant.parse("2026-10-07T01:02:03.456Z")

    fun newOrder(environment: Environment = Environment.TEST): OrderKey =
        OrderKey(environment, MerchantAccountId(unique("mid")), MerchantOrderId(unique("order")))

    fun intent(amount: Long = 10_000, currency: CurrencyCode = KRW, site: MerchantSiteId = demoSite) = OrderIntent(site, Money(amount, currency))

    fun newPaymentId(): PaymentId = PaymentId(unique("pay"))

    fun requested(order: OrderKey, intent: OrderIntent = intent(), slotGeneration: Long = 1, id: PaymentId = newPaymentId()): Payment =
        Payment.request(id, order, intent.fingerprint(), slotGeneration, demoWindow)

    fun attributes(
        intent: OrderIntent = intent(),
        paymentKey: PaymentKey = PaymentKey(unique("pk")),
        orderName: String? = "Demo order",
        createdAt: Instant = t0,
    ) = PaymentCreationAttributes(paymentKey, intent.merchantSiteId, intent.money, PaymentMethod.CARD, orderName, snapshot, createdAt, demoActor)

    /** 시험 Payment의 결제창 시각(Demo — t0 + 30분, 설정 버전 Demo). DB CHECK `checkout_expires_at > created_at`을 만족한다. */
    val demoWindow = CheckoutWindow(t0.plusSeconds(1800), "checkout-validity-demo-1")

    /** 시험 전용 생성 행위자 keyId(Demo). */
    val demoActor = CredentialKeyId("demoActor0001")
}

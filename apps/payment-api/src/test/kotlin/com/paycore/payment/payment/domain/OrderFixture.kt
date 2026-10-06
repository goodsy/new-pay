package com.paycore.payment.payment.domain

/**
 * Demo 주문 fixture(Named Test Fixture). 값은 시험용 식별자·Demo 금액이며 운영 MID/Site/주문/금액이 아니다.
 *
 * 고정 주문 입력은 D-CPC-001(merchantSiteId + amount + currency)이고 fingerprint는 CP-CONTRACT-001 §6 v1 정의
 * ([OrderIntent.fingerprint])로 실제 계산한다. 각 "different*" 입력은 원 주문과 한 필드만 다르다.
 */
object OrderFixture {
    val demoMid = MerchantAccountId("demo-mid-001")
    val demoOrderId = MerchantOrderId("demo-order-0001")

    /** TEST 환경 Demo 주문. */
    val demoOrder = OrderKey(Environment.TEST, demoMid, demoOrderId)

    /** 전액 반환 뒤 재구매처럼 새 merchantOrderId로 시작하는 다른 주문. */
    val newPurchaseOrder = OrderKey(Environment.TEST, demoMid, MerchantOrderId("demo-order-0002"))

    val demoSite = MerchantSiteId("demo-site-001")

    /** 원 주문 고정 입력: Demo Site + 10,000 KRW. */
    val originalIntent = OrderIntent(demoSite, Money(10_000, MoneyFixture.KRW)).fingerprint()

    /** 원 주문과 금액만 다른 고정 입력. */
    val differentAmountIntent = OrderIntent(demoSite, Money(10_001, MoneyFixture.KRW)).fingerprint()

    /** 원 주문과 통화만 다른 고정 입력(minor unit 숫자는 같은 10,000 → USD 100.00). */
    val differentCurrencyIntent = OrderIntent(demoSite, Money(10_000, MoneyFixture.USD)).fingerprint()

    /** 원 주문과 Site만 다른 고정 입력. */
    val differentSiteIntent = OrderIntent(MerchantSiteId("demo-site-002"), Money(10_000, MoneyFixture.KRW)).fingerprint()

    fun paymentId(n: Int) = PaymentId("demo-pay-%04d".format(n))
}

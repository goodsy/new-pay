package com.paycore.payment.payment.domain

/**
 * CP-DOMAIN-001 Demo 주문 fixture(Named Test Fixture). 값은 시험용 식별자이며 운영 MID/주문/금액이 아니다.
 *
 * 고정 주문 입력(금액·통화·Site 등)의 실제 fingerprint 계산(필드·표현·버전)은 CP-CONTRACT-001에서 고정한다(입력 규칙은 D-CP-001
 * ID 그대로 비교, D-CP-002 통화·minor unit). domain에는 고정하지 않는다.
 * application이 계산했다고 가정한 불투명 fingerprint 값(fixture 동일성)으로만 시험한다. 실제 API 동일성은 아직 고정하지 않는다.
 */
object OrderFixture {
    val demoMid = MerchantAccountId("demo-mid-001")
    val demoOrderId = MerchantOrderId("demo-order-0001")

    /** TEST 환경 Demo 주문. */
    val demoOrder = OrderKey(Environment.TEST, demoMid, demoOrderId)

    /** 전액 반환 뒤 재구매처럼 새 merchantOrderId로 시작하는 다른 주문. */
    val newPurchaseOrder = OrderKey(Environment.TEST, demoMid, MerchantOrderId("demo-order-0002"))

    /** 원 주문 고정 입력(예: Demo 금액·Demo 통화·Demo Site를 application이 계산한 값이라고 가정). */
    val originalIntent = OrderIntentFingerprint("fixture:intent-original")

    /** 원 주문과 금액만 다른 고정 입력. */
    val differentAmountIntent = OrderIntentFingerprint("fixture:intent-different-amount")

    /** 원 주문과 통화만 다른 고정 입력. */
    val differentCurrencyIntent = OrderIntentFingerprint("fixture:intent-different-currency")

    /** 원 주문과 Site만 다른 고정 입력. */
    val differentSiteIntent = OrderIntentFingerprint("fixture:intent-different-site")

    fun paymentId(n: Int) = PaymentId("demo-pay-%04d".format(n))
}

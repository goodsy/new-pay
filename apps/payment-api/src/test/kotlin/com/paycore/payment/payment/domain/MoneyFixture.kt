package com.paycore.payment.payment.domain

/**
 * 금액·통화 Named Test Fixture(Test Fixture Policy). D-CP-002의 초기 구현·검증 통화 KRW(minor unit 0)·USD(minor unit 2)만 쓴다.
 *
 * 이 값은 시험 fixture다. 운영의 허용 통화와 minor unit은 MID·기관 계약 기준정보(MASTER)에서 오며 코드에 허용 통화 목록을 두지 않는다.
 * 금액도 Demo 값이며 운영 한도가 아니다(D-CPC-005: 업무 한도는 기준정보 정책).
 */
object MoneyFixture {
    val KRW = CurrencyCode("KRW")
    val USD = CurrencyCode("USD")

    /** 기준정보가 준다고 가정한 KRW 단위(minor unit 0). */
    val krwUnit = CurrencyUnit(KRW, minorUnit = 0)

    /** 기준정보가 준다고 가정한 USD 단위(minor unit 2). */
    val usdUnit = CurrencyUnit(USD, minorUnit = 2)

    /** 원 단위 금액(KRW minor unit = 원). */
    fun Long.won(): Money = Money(this, KRW)

    /** 센트 단위 금액(USD minor unit = cent). */
    fun Long.usdCents(): Money = Money(this, USD)
}

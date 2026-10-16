package com.paycore.payment.payment.domain

import java.math.BigDecimal

/**
 * 결제 요청 금액(D-CP-002, D-CPC-005, 사용자 확정 2026-10-06).
 *
 * - [amountMinor]는 통화 minor unit 정수다(KRW 1원 = 1, USD 1센트 = 1). Float/Double을 쓰지 않는다.
 * - 1 이상만 허용하고 상한은 BIGINT(Long) 표현 한계뿐이다. 건당·일 한도 같은 업무 상한은 MID·계약 기준정보 한도 정책이며 코드에
 *   운영 상한을 두지 않는다.
 * - 0·음수가 필요한 금액(조정·잔액 등)은 이 타입이 아니라 해당 작업에서 별도 타입으로 정의한다.
 */
data class Money(val amountMinor: Long, val currency: CurrencyCode) {
    init {
        require(amountMinor >= 1) { "amountMinor must be at least 1" }
    }

    /**
     * 기준정보 [unit]의 minor unit으로 주(major) 단위 10진 문자열을 만든다(예: USD 1050 → "10.50"). 표시 전용이며 금액 비교·계산·
     * fingerprint에는 쓰지 않는다(정수 minor unit이 기준).
     */
    fun toMajorUnitString(unit: CurrencyUnit): String {
        require(unit.code == currency) { "currency unit ${unit.code.value} does not match ${currency.value}" }
        return BigDecimal.valueOf(amountMinor).movePointLeft(unit.minorUnit).toPlainString()
    }
}

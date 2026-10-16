package com.paycore.payment.payment.domain

/**
 * 기준정보가 주는 통화 단위: 코드와 minor unit(소수 자릿수, ISO 4217 기준 0..4. 예: KRW 0, USD 2).
 * minor unit은 코드에 상수로 두지 않고 기준정보(MASTER Snapshot, CP-CONTRACT-001 §13 `currencyUnit`)에서 받는다.
 */
data class CurrencyUnit(val code: CurrencyCode, val minorUnit: Int) {
    init {
        require(minorUnit in 0..MAX_ISO_4217_MINOR_UNIT) { "minorUnit must be within 0..$MAX_ISO_4217_MINOR_UNIT: $minorUnit" }
    }

    private companion object {
        /** ISO 4217 목록의 최대 minor unit(CLF·UYW = 4). 데이터 유효 범위이며 업무 정책이 아니다. */
        const val MAX_ISO_4217_MINOR_UNIT = 4
    }
}

package com.paycore.master.merchant.domain

/**
 * ISO 4217 통화 단위(기준정보 `currency_unit`). **허용 통화가 아니다** — 어떤 MID가 그 통화를 쓸 수 있는지는 [CurrencyEnablement]가 정한다
 * (D-MD-018, D-CP-002).
 */
data class CurrencyUnit(val code: CurrencyCode, val minorUnit: Int) {
    init {
        require(minorUnit in 0..MAX_MINOR_UNIT) { "minorUnit must be within 0..$MAX_MINOR_UNIT" }
    }

    private companion object {
        const val MAX_MINOR_UNIT = 4
    }
}

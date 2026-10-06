package com.paycore.payment.payment.domain

import java.math.BigDecimal

/**
 * ISO 4217 alphabetic 통화 코드(대문자 3자). 대소문자 변환·trim 없이 형식만 검사한다(CP-CONTRACT-001 §3·§4).
 *
 * 어떤 통화를 허용할지는 코드가 아니라 MID·기관 계약 기준정보가 정한다(D-CP-002, 사용자 확정 2026-10-06). 그래서 이 타입은 허용
 * 목록을 갖지 않는다. 초기 구현·검증 통화(KRW·USD)는 시험 fixture에만 있다.
 */
@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.length == 3 && value.all { it in 'A'..'Z' }) { "currency must be an ISO 4217 alphabetic code (3 upper-case letters)" }
    }
}

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

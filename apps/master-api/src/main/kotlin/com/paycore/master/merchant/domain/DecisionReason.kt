package com.paycore.master.merchant.domain

/**
 * 상태 전이·철회 사유(D-MS-002·003 "사유·행위자"). 운영자가 남기는 짧은 코드/설명: 1~200 code point, 제어문자·짝 없는 서로게이트 금지,
 * 앞뒤 공백 금지. 사유에 카드정보·Secret·개인정보를 넣지 않는다(운영 지침 — 형식으로는 강제하지 못한다).
 */
@JvmInline
value class DecisionReason(val value: String) {
    init {
        require(value.isNotEmpty() && value.codePointCount(0, value.length) <= MAX_CODE_POINTS) { "reason must be 1..$MAX_CODE_POINTS characters" }
        require(value.first() != ' ' && value.last() != ' ') { "reason must not start or end with a space" }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            require(!Character.isISOControl(c)) { "reason must not contain control characters" }
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) { "reason must be well-formed UTF-16" }
                i += 2
                continue
            }
            require(!Character.isLowSurrogate(c)) { "reason must be well-formed UTF-16" }
            i++
        }
    }

    private companion object {
        const val MAX_CODE_POINTS = 200
    }
}

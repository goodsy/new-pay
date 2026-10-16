package com.paycore.master.merchant.domain

/** 표시 이름: 1~100 code point, 제어문자·짝 없는 서로게이트 금지, 앞뒤 공백 금지(암묵 trim 없음 — 그대로 거절). */
@JvmInline
value class DisplayName(val value: String) {
    init {
        require(value.isNotEmpty() && value.codePointCount(0, value.length) <= MAX_CODE_POINTS) { "displayName must be 1..$MAX_CODE_POINTS characters" }
        require(value.first() != ' ' && value.last() != ' ') { "displayName must not start or end with a space" }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            require(!Character.isISOControl(c)) { "displayName must not contain control characters" }
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) { "displayName must be well-formed UTF-16" }
                i += 2
                continue
            }
            require(!Character.isLowSurrogate(c)) { "displayName must be well-formed UTF-16" }
            i++
        }
    }

    private companion object {
        const val MAX_CODE_POINTS = 100
    }
}

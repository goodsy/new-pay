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

    companion object {
        private const val MAX_CODE_POINTS = 100

        /**
         * 입력 해석기(BUSINESS-PROFILE-001 BIZ-2 — 상호 `displayName`·검색 접두)용 판정: 생성자와 같은 집합(빈 값 REQUIRED, 제어문자·짝 없는 서로게이트·앞뒤 공백 FORMAT,
         * 100 code point 초과 TOO_LONG — [BusinessTextRule]). null = 통과.
         */
        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.errorOf(raw, MAX_CODE_POINTS)
    }
}

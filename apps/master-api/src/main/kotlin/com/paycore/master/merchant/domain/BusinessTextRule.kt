package com.paycore.master.merchant.domain

/**
 * 사업자 문자열 값 공통 규칙(BUSINESS-PROFILE-001 §4.2 — [DisplayName]과 같은 문자 규칙). 정규화·암묵 trim 없이 그대로 판정한다:
 * 빈 값 = REQUIRED, 제어문자(줄바꿈 포함)·짝 없는 서로게이트·앞뒤 공백 = FORMAT, code point 상한 초과 = TOO_LONG. 오류에 입력 값을 담지 않는다.
 */
internal object BusinessTextRule {
    fun errorOf(raw: String, maxCodePoints: Int): BusinessFieldErrorCode? {
        if (raw.isEmpty()) return BusinessFieldErrorCode.REQUIRED
        if (!wellFormed(raw)) return BusinessFieldErrorCode.FORMAT
        if (raw.first() == ' ' || raw.last() == ' ') return BusinessFieldErrorCode.FORMAT
        if (raw.codePointCount(0, raw.length) > maxCodePoints) return BusinessFieldErrorCode.TOO_LONG
        return null
    }

    /** ASCII 숫자만 정확히 [length]자(빈 값 REQUIRED, 그 밖 FORMAT). */
    fun digitsErrorOf(raw: String, length: Int): BusinessFieldErrorCode? = when {
        raw.isEmpty() -> BusinessFieldErrorCode.REQUIRED
        raw.length != length || !raw.all { it in '0'..'9' } -> BusinessFieldErrorCode.FORMAT
        else -> null
    }

    /** 생성자 검사: 위반이면 code 이름만 담은 IllegalArgumentException(값 없음). */
    fun require(type: String, code: BusinessFieldErrorCode?) {
        require(code == null) { "$type is invalid: $code" }
    }

    private fun wellFormed(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isISOControl(c)) return false
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }
}
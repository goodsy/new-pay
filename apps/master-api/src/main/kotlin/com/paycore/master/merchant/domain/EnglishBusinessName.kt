package com.paycore.master.merchant.domain

/** 영문 상호(§4.2): 1~100자 출력 가능 ASCII(0x20..0x7E), 앞뒤 공백 금지. 정규화 없음. */
@JvmInline
value class EnglishBusinessName(val value: String) {
    init {
        BusinessTextRule.require("english business name", errorOf(value))
    }

    companion object {
        const val MAX_LENGTH = 100

        fun errorOf(raw: String): BusinessFieldErrorCode? = when {
            raw.isEmpty() -> BusinessFieldErrorCode.REQUIRED
            !raw.all { it in ' '..'~' } || raw.first() == ' ' || raw.last() == ' ' -> BusinessFieldErrorCode.FORMAT
            raw.length > MAX_LENGTH -> BusinessFieldErrorCode.TOO_LONG
            else -> null
        }
    }
}
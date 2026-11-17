package com.paycore.master.merchant.domain

/**
 * 전화번호(§4.2·C-20 — 형식만, 실재 확인 없음): ASCII 숫자 8~12자리, 첫 자리 `0` 또는 `1`(대표번호 15xx 등). 하이픈은 화면 표시만(정규화 없음 — X-14).
 * 유선·휴대 구분 없음. 개인정보 — [toString]은 끝 4자리만.
 */
@JvmInline
value class PhoneNumber(val value: String) {
    init {
        BusinessTextRule.require("phone number", errorOf(value))
    }

    override fun toString(): String = "PhoneNumber(${BusinessPiiMask.phone(value)})"

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 12

        fun errorOf(raw: String): BusinessFieldErrorCode? = when {
            raw.isEmpty() -> BusinessFieldErrorCode.REQUIRED
            !raw.all { it in '0'..'9' } -> BusinessFieldErrorCode.FORMAT
            raw.length > MAX_LENGTH -> BusinessFieldErrorCode.TOO_LONG
            raw.length < MIN_LENGTH || (raw[0] != '0' && raw[0] != '1') -> BusinessFieldErrorCode.FORMAT
            else -> null
        }
    }
}
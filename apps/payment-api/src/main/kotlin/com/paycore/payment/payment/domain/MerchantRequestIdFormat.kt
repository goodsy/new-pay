package com.paycore.payment.payment.domain

/**
 * merchantOrderId·멱등키 형식(D-CPC-002, 사용자 확정 2026-10-06): 1~64자, `[A-Za-z0-9-_.:]`만. 공백·비ASCII·제어문자는 문자 집합
 * 밖이라 거절된다. 대소문자는 구분한다(변환 없음). 오류 메시지에 입력값을 넣지 않는다(로그 주입·입력 노출 방지).
 */
internal object MerchantRequestIdFormat {
    const val MAX_LENGTH = 64

    fun isValid(value: String): Boolean = value.length in 1..MAX_LENGTH && value.all(::isAllowed)

    fun requireValid(value: String, field: String) {
        require(isValid(value)) { "$field must be 1..$MAX_LENGTH characters of [A-Za-z0-9-_.:]" }
    }

    private fun isAllowed(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == ':'
}

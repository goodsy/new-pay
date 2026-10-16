package com.paycore.master.merchant.application.provided

/**
 * 관리 명령 멱등키(형식은 D-CPC-002와 같은 관례: 1~64자 `[A-Za-z0-9-_.:]`, 대소문자 구분, 입력 그대로 비교).
 * 오류 메시지에 입력값을 넣지 않는다.
 */
@JvmInline
value class AdminIdempotencyKey(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH && value.all(::isAllowed)) { "idempotency key must be 1..$MAX_LENGTH characters of [A-Za-z0-9-_.:]" }
    }

    private companion object {
        const val MAX_LENGTH = 64

        fun isAllowed(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == ':'
    }
}

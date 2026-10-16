package com.paycore.master.merchant.domain

/**
 * 명령을 낸 운영자/시스템의 불투명 참조(Q-ADMIN-AUTH 미결 — 인증·권한 검증 없이 **기록만** 한다). 1~64자 `[A-Za-z0-9._:@-]`.
 * 인증된 신원이 아니므로 요청자≠승인자 같은 통제의 근거로 쓰지 않는다(관리자 인증 결정 후 web adapter가 신원을 채운다).
 */
@JvmInline
value class ActorRef(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH && value.all(::isAllowed)) { "actor must be 1..$MAX_LENGTH characters of [A-Za-z0-9._:@-]" }
    }

    private companion object {
        const val MAX_LENGTH = 64

        fun isAllowed(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == ':' || c == '@' || c == '-'
    }
}

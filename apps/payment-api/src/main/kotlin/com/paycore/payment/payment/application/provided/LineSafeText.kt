package com.paycore.payment.payment.application.provided

/**
 * 가맹점이 보낸 문자열 값의 거절 문자 규칙(CP-CONTRACT-001 §3.1, CP-API-001 명세 §4.3·§4.4): 제어문자 Cc 전부(탭·개행 포함), 줄·문단 구분자
 * U+2028·U+2029, 양방향 embedding/override/isolate U+202A–U+202E·U+2066–U+2069. 로그 줄 분리·표시 위조를 막는다(review round2 m-2).
 * orderName·merchantAccountId·merchantSiteId에 쓴다(merchantOrderId·멱등키는 D-CPC-002 ASCII 형식으로 이미 막힌다).
 */
object LineSafeText {
    fun hasForbiddenCharacter(value: String): Boolean = value.codePoints().anyMatch(::isForbidden)

    fun isForbidden(cp: Int): Boolean =
        Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp in 0x202A..0x202E || cp in 0x2066..0x2069
}

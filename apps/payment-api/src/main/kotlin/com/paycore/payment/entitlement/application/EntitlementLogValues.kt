package com.paycore.payment.entitlement.application

import org.slf4j.MDC

/**
 * entitlement 로그 값 도구(CP-API-001 review round2 m-2·n-e).
 *
 * - [safe]: 외부(가맹점 요청·MASTER 봉투) 유래 식별자를 로그 한 줄 안에 가두는 방어적 이스케이프. 제어문자 Cc·U+2028/2029·bidi 제어
 *   (U+202A–U+202E, U+2066–U+2069)를 `\uXXXX` 표기로, 백슬래시를 두 개로 바꾼다. 공개 API 파서가 이미 이 문자들을 400으로 거절하지만, 다른
 *   호출 경로가 생겨도 로그 줄 주입이 되지 않게 한다. 길이는 [MAX_LENGTH]에서 자르고 `…`를 붙인다.
 * - [correlationId]: 요청 스레드 MDC의 상관 ID(웹 진입 필터가 넣는다 — payment 웹 `ApiResponses.CORRELATION_ID_MDC_KEY`와 같은 키). 없으면 null.
 */
internal object EntitlementLogValues {
    const val CORRELATION_ID_MDC_KEY = "correlationId"
    private const val MAX_LENGTH = 128
    private const val BACKSLASH = '\\'

    fun correlationId(): String? = MDC.get(CORRELATION_ID_MDC_KEY)

    fun safe(value: String?): String? {
        if (value == null) return null
        val clipped = value.length > MAX_LENGTH
        val source = if (clipped) value.substring(0, MAX_LENGTH) else value
        val escaped = buildString {
            source.forEach { c ->
                when {
                    c == BACKSLASH -> append(BACKSLASH).append(BACKSLASH)
                    isUnsafe(c.code) -> append(BACKSLASH).append('u').append("%04X".format(c.code))
                    else -> append(c)
                }
            }
        }
        return if (clipped) "$escaped…" else escaped
    }

    private fun isUnsafe(cp: Int): Boolean =
        Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || cp in 0x202A..0x202E || cp in 0x2066..0x2069
}

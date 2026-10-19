package com.paycore.simulator.van.domain

import java.time.Instant

/**
 * ULID 텍스트(Crockford base32 26자: 시각 48bit 10자 + 나머지 80bit 16자). 가상 VAN은 요청 ID(`ar_` + ULID)의 시각으로 요청 연령을 본다 — 받은 적 없는 요청 ID도
 * 무효과 종결 가능 시점(`closableAfter`)과 조회 보존기간을 판정할 수 있다(AUTH-001 §5.4). 형식이 아니면 시각 없음(null).
 */
object VanUlid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_CHARS = 10
    private const val LENGTH = 26
    private const val BITS = 5
    private val TEXT = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    fun isUlid(text: String): Boolean = TEXT.matches(text)

    /** [prefixed]가 `xx_` + ULID면 그 시각, 아니면 null. */
    fun timeOf(prefixed: String): Instant? {
        val ulid = prefixed.substringAfter('_', missingDelimiterValue = "")
        if (!isUlid(ulid)) return null
        val millis = ulid.take(TIME_CHARS).fold(0L) { acc, c -> (acc shl BITS) or ALPHABET.indexOf(c).toLong() }
        return Instant.ofEpochMilli(millis)
    }

    /** 시각 [at]과 나머지 80bit 값 [low](하위 64bit만 사용 — 상위 16bit는 0)으로 ULID 텍스트를 만든다. */
    fun format(at: Instant, low: Long): String = format(at.toEpochMilli(), 0L, low)

    fun format(at: Instant, low: Int): String = format(at, low.toLong())

    /** [millis] 48bit, [high] 16bit, [low] 64bit. */
    fun format(millis: Long, high: Long, low: Long): String {
        require(millis in 0..0xFFFF_FFFF_FFFFL) { "ULID time must fit 48 bits" }
        val out = CharArray(LENGTH)
        var t = millis
        for (i in TIME_CHARS - 1 downTo 0) {
            out[i] = ALPHABET[(t and 0x1F).toInt()]
            t = t ushr BITS
        }
        // 80bit = high(16) ‖ low(64) → 16자
        var lo = low
        var hi = high and 0xFFFF
        for (i in LENGTH - 1 downTo TIME_CHARS) {
            out[i] = ALPHABET[(lo and 0x1F).toInt()]
            lo = (lo ushr BITS) or ((hi and 0x1F) shl (64 - BITS))
            hi = hi ushr BITS
        }
        return String(out)
    }
}

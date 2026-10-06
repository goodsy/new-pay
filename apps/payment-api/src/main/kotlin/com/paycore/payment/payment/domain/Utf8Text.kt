package com.paycore.payment.payment.domain

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 업무 문자열의 Unicode 정합성과 fingerprint용 엄격 UTF-8 인코딩(CP-CONTRACT-001 §3.1·§6.2, Reviewer L-3).
 *
 * `String.toByteArray(UTF_8)`는 짝 없는 서로게이트를 `?`로 **치환**해 `"a\uD800"`과 `"a?"`가 같은 bytes·같은 v1 fingerprint가 된다.
 * 그래서 (1) 입력 그대로 비교하는 문자열 값 객체는 생성 시 짝 없는 서로게이트를 거부하고([requireWellFormed]), (2) fingerprint
 * 인코딩은 malformed 입력을 치환하지 않고 보고한다([encode], 최후 방어선). well-formed 입력의 bytes는 표준 UTF-8과 같으므로
 * v1 golden vector는 바뀌지 않는다. 예외 메시지에 입력값을 넣지 않는다.
 */
internal object Utf8Text {

    /** 모든 high surrogate 뒤에 low surrogate가 오고, 앞에 high가 없는 low surrogate가 없으면 true. */
    fun isWellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 < s.length && s[i + 1].isLowSurrogate()) i += 2 else return false
                }
                c.isLowSurrogate() -> return false
                else -> i++
            }
        }
        return true
    }

    fun requireWellFormed(value: String, field: String) {
        require(isWellFormed(value)) { "$field must be well-formed Unicode text (no unpaired surrogate)" }
    }

    /** 엄격 UTF-8: malformed·unmappable 입력을 REPORT한다(치환 금지). 위반 시 [IllegalArgumentException]. */
    fun encode(s: String): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buffer = try {
            encoder.encode(CharBuffer.wrap(s))
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("text must be well-formed Unicode (no unpaired surrogate)", e)
        }
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
}

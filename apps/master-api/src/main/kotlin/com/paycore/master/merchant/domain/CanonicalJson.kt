package com.paycore.master.merchant.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 정규 JSON 작성기(관례, 명세 §10 contentHash): 객체 키는 사전순(UTF-16 code unit 순, 키는 ASCII), 공백 없음, 시각은 UTC ms ISO-8601
 * `uuuu-MM-dd'T'HH:mm:ss.SSS'Z'`, 정수는 10진, null은 `null`, 문자열은 `"`·`\`·제어문자(U+0000–U+001F)만 escape(`\uXXXX` 소문자 hex)하고
 * 나머지는 그대로(UTF-8). PAYMENT는 같은 규칙으로 다시 계산해 비교한다(계약 golden — S7).
 */
object CanonicalJson {
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun write(value: Map<String, Any?>): String = StringBuilder().also { append(it, value) }.toString()

    fun timestamp(at: Instant): String = TIMESTAMP.format(at)

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long -> out.append(value)
            is Instant -> string(out, timestamp(value))
            is Map<*, *> -> {
                out.append('{')
                value.entries.map { (k, v) -> (k as String) to v }.sortedBy { it.first }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    string(out, k)
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    append(out, v)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("unsupported canonical JSON value type: ${value::class.simpleName}")
        }
    }

    private fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
                else -> out.append(c)
            }
        }
        out.append('"')
    }
}

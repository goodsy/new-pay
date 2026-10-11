package com.paycore.master.merchant.adapter.persistence

import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Outbox payload·변경 이력 detail 직렬화(형식은 adapter 책임). 시각은 UTC ms ISO-8601(`…T01:02:03.456Z`), 정수는 JSON 정수,
 * 열거·식별자는 문자열이다. 지원하지 않는 값 타입은 결함으로 거부한다(직렬화 형식이 조용히 바뀌지 않게).
 */
internal object MasterJson {
    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun serialize(values: Map<String, Any?>): String = mapper.writeValueAsString(normalize(values))

    private fun normalize(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long -> value
        is Instant -> TIMESTAMP.format(value)
        is Map<*, *> -> value.entries.associate { (k, v) -> k as String to normalize(v) }.toSortedMap()
        is Iterable<*> -> value.map(::normalize)
        else -> throw IllegalArgumentException("unsupported JSON value type: ${value::class.simpleName}")
    }
}

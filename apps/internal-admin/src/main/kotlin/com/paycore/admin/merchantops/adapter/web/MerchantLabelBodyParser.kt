package com.paycore.admin.merchantops.adapter.web

import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 가맹점 라벨 본문 구조 해석(MERCHANT-CODE-001 §5.6.3): JSON 객체, 필드는 `merchantIds` 하나만(모르는 필드·중복 key·뒤따르는 토큰 거부), 값은 문자열 배열.
 * 개수(1..50)·형식·중복 판정은 Use Case가 한다(MASTER 호출 전). 어긋나면 null — 본문 원문은 어디에도 넘기지 않는다.
 */
object MerchantLabelBodyParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private const val FIELD = "merchantIds"

    fun parse(body: ByteArray): List<String>? = runCatching {
        val root = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().toSet() != setOf(FIELD)) return null
        val array = root.get(FIELD)
        if (!array.isArray) return null
        (0 until array.size()).map { i -> array.get(i).takeIf { it.isString }?.asString() ?: return null }
    }.getOrNull()
}

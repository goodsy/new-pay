package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MERCHANT_LABELS_MAX
import com.paycore.master.merchant.domain.MerchantId
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * 가맹점 라벨 묶음 본문 엄격 해석(MERCHANT-CODE-001 §5.6.3): JSON 객체, 필드는 `merchantIds` 하나만(모르는 필드·중복 key·뒤따르는 토큰 거부), 값은 문자열 배열 1..50개,
 * 각 값은 가맹점 내부 키 형식(형식 규칙은 Domain 값 객체 [MerchantId] 한 곳), 중복 금지. 하나라도 어긋나면 null(400 `REQUEST_MALFORMED` — 어느 값인지 응답·로그에 넣지 않는다).
 */
object MerchantLabelRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private const val FIELD = "merchantIds"

    fun parse(body: ByteArray): List<MerchantId>? = runCatching {
        val root = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().toSet() != setOf(FIELD)) return null
        val array = root.get(FIELD)
        if (!array.isArray || array.size() !in 1..MERCHANT_LABELS_MAX) return null
        val ids = (0 until array.size()).map { i ->
            val node = array.get(i)
            require(node.isString) { "type" }
            MerchantId(node.asString())
        }
        if (ids.toSet().size != ids.size) return null
        ids
    }.getOrNull()
}

package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MERCHANT_ACCOUNT_LABELS_MAX
import com.paycore.master.merchant.domain.MerchantAccountId
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * 라벨 묶음 본문 엄격 해석(TXN-LIST-ENRICH-001 §4.2): JSON 객체, 필드는 `merchantAccountIds` 하나만(모르는 필드·중복 key·뒤따르는 토큰 거부), 값은 문자열 배열 1..50개, 각 값은 MID 형식
 * (N-4 — 형식 규칙은 Domain 값 객체 [MerchantAccountId] 한 곳), 중복 금지. 하나라도 어긋나면 null(400 `REQUEST_MALFORMED` — 어느 값인지 응답·로그에 넣지 않는다).
 */
object MerchantAccountLabelRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private const val FIELD = "merchantAccountIds"

    fun parse(body: ByteArray): List<MerchantAccountId>? = runCatching {
        val root = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().toSet() != setOf(FIELD)) return null
        val array = root.get(FIELD)
        if (!array.isArray || array.size() !in 1..MERCHANT_ACCOUNT_LABELS_MAX) return null
        val ids = (0 until array.size()).map { i ->
            val node = array.get(i)
            require(node.isString) { "type" }
            MerchantAccountId(node.asString())
        }
        if (ids.toSet().size != ids.size) return null
        ids
    }.getOrNull()
}

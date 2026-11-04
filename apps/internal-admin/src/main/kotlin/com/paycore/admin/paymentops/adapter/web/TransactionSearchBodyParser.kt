package com.paycore.admin.paymentops.adapter.web

import com.paycore.admin.paymentops.application.provided.TransactionSearchCriteria
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 거래 검색 본문 엄격 해석(TXN-OPS-001 §4.1 — MERCHANT-OPS-001 `MerchantSearchBodyParser`와 같은 자료형 규칙): JSON 객체 하나, 알려진 필드만, 중복 key·뒤따르는 토큰 거부,
 * 자료형 강제 변환 없음(문자열 필드는 JSON 문자열, `limit`은 JSON 정수, `after`는 정확히 `createdAt`·`paymentId` 두 문자열 키의 객체, `null` 거부). 값의 **형식**(시각·ID·범위)과
 * 모드 규칙은 Use Case가 PAYMENT 호출 전에 검사한다. 실패는 null(본문 원문·값은 넘기지 않는다 — 로그·응답 비노출, 목록 검색은 감사 대상이 아니다).
 */
object TransactionSearchBodyParser {

    private val TEXT_FIELDS = setOf("createdFrom", "createdTo", "environment", "merchantAccountId", "merchantOrderId", "paymentId", "status")
    private val CURSOR_FIELDS = setOf("createdAt", "paymentId")

    private val strict: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parse(body: ByteArray): TransactionSearchCriteria? {
        val root = try {
            strict.readTree(body)
        } catch (e: RuntimeException) {
            null
        }
        return criteria(root)
    }

    private fun criteria(root: JsonNode?): TransactionSearchCriteria? {
        if (root == null || !root.isObject) return null
        val names = root.propertyNames().toList()
        if (names.any { it !in TEXT_FIELDS && it != "after" && it != "limit" }) return null
        if (names.any { it in TEXT_FIELDS && !root.get(it).isString }) return null
        val limit = root.get("limit")
        if (limit != null && !(limit.isIntegralNumber && limit.canConvertToInt())) return null
        val after = root.get("after")
        if (after != null && !(after.isObject && after.propertyNames().toSet() == CURSOR_FIELDS && CURSOR_FIELDS.all { after.get(it).isString })) return null
        fun text(name: String): String? = root.get(name)?.asString()
        return TransactionSearchCriteria(
            createdFrom = text("createdFrom"), createdTo = text("createdTo"), environment = text("environment"), merchantAccountId = text("merchantAccountId"),
            merchantOrderId = text("merchantOrderId"), paymentId = text("paymentId"), status = text("status"),
            afterCreatedAt = after?.get("createdAt")?.asString(), afterPaymentId = after?.get("paymentId")?.asString(), limit = limit?.asInt(),
        )
    }
}

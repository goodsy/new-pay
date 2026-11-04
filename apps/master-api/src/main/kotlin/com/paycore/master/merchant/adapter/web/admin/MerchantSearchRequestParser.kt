package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MerchantSearchFilter
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.Lifecycle
import com.paycore.master.merchant.domain.MerchantId
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 가맹점 검색 본문 엄격 해석(MERCHANT-OPS-001 §3.1): JSON 객체, 모든 필드 선택, 모르는 필드·중복 key·뒤따르는 토큰·자료형 강제 변환 거부. ID는 `<prefix>_` + ULID,
 * `lifecycle` ACTIVE·TERMINATED, `registrationNumber`는 ASCII 숫자 10자리, `limit` JSON 정수 1..50. 하나라도 어긋나면 null(400 `REQUEST_MALFORMED` — 어느 필드인지·입력 값을
 * 응답·로그에 넣지 않는다: 등록번호를 되돌려 주지 않는 oracle 방어 §5.5).
 */
object MerchantSearchRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val FIELDS = setOf("merchantId", "businessId", "lifecycle", "registrationNumber", "after", "limit")
    private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    val MERCHANT_ID = Regex("^mer_$ULID$")
    private val BUSINESS_ID = Regex("^biz_$ULID$")
    private val REGISTRATION_NUMBER = Regex("^[0-9]{10}$")

    fun parse(body: ByteArray): MerchantSearchFilter? = runCatching {
        val root = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().any { it !in FIELDS }) return null
        val limit = root.get("limit")?.let { if (it.isIntegralNumber && it.canConvertToInt()) it.asInt() else return null } ?: MerchantSearchFilter.DEFAULT_LIMIT
        if (limit !in 1..MerchantSearchFilter.MAX_LIMIT) return null
        MerchantSearchFilter(
            merchantId = text(root, "merchantId", MERCHANT_ID)?.let(::MerchantId),
            businessId = text(root, "businessId", BUSINESS_ID)?.let(::BusinessId),
            lifecycle = text(root, "lifecycle", LIFECYCLE)?.let(Lifecycle::valueOf),
            registrationNumber = text(root, "registrationNumber", REGISTRATION_NUMBER)?.let(::BusinessRegistrationNumber),
            after = text(root, "after", MERCHANT_ID)?.let(::MerchantId),
            limit = limit,
        )
    }.getOrNull()

    private val LIFECYCLE = Regex("^(ACTIVE|TERMINATED)$")

    /** 필드가 없으면 null, 있으면 문자열·형식 일치여야 한다(아니면 예외 → 전체 null). */
    private fun text(root: JsonNode, name: String, format: Regex): String? {
        val node = root.get(name) ?: return null
        require(node.isString) { "type" }
        return node.asString().also { require(format.matches(it)) { "format" } }
    }
}

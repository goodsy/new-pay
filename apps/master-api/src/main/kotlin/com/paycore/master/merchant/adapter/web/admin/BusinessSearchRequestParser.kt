package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.BusinessSearchFilter
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationStatus
import com.paycore.master.merchant.domain.BusinessType
import com.paycore.master.merchant.domain.DisplayName
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * 사업자 목록 검색 본문 엄격 해석(BUSINESS-PROFILE-001 §6.2 — [MerchantSearchRequestParser]와 같은 규칙): JSON 객체, **모든 필드 선택**, 모르는 필드·중복 key·뒤따르는 토큰·
 * 자료형 강제 변환 거부. `displayNamePrefix`는 상호 문자 규칙([DisplayName]), `businessType`·`registrationStatus`는 enum 이름, `createdFrom`·`createdTo`는 KST 달력 날짜
 * `yyyy-MM-dd`(실재 날짜, from ≤ to), `after`는 `biz_` + ULID, `limit`은 JSON 정수 1..50(기본 20). **`registrationNumber` 필드는 없다**(번호 검색은 번호 조회만 — C-6,
 * 보내면 모르는 필드 400). 어긋나면 null(400 `REQUEST_MALFORMED` — 필드·값 미반환).
 */
object BusinessSearchRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val FIELDS = setOf("displayNamePrefix", "businessType", "registrationStatus", "createdFrom", "createdTo", "after", "limit")
    val BUSINESS_ID = Regex("^biz_[0-9A-HJKMNP-TV-Z]{26}$")
    private val DATE_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    fun parse(body: ByteArray): BusinessSearchFilter? = runCatching {
        val root = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().any { it !in FIELDS }) return null
        val limit = root.get("limit")?.let { if (it.isIntegralNumber && it.canConvertToInt()) it.asInt() else return null } ?: BusinessSearchFilter.DEFAULT_LIMIT
        if (limit !in 1..BusinessSearchFilter.MAX_LIMIT) return null
        val prefix = text(root, "displayNamePrefix")?.also { if (DisplayName.errorOf(it) != null) return null }
        BusinessSearchFilter(
            displayNamePrefix = prefix?.let(::DisplayName),
            businessType = text(root, "businessType")?.let { v -> BusinessType.entries.single { it.name == v } },
            registrationStatus = text(root, "registrationStatus")?.let { v -> BusinessRegistrationStatus.entries.single { it.name == v } },
            createdFrom = text(root, "createdFrom")?.let(::date),
            createdTo = text(root, "createdTo")?.let(::date),
            after = text(root, "after")?.also { require(BUSINESS_ID.matches(it)) }?.let(::BusinessId),
            limit = limit,
        )
    }.getOrNull()

    /** 필드가 없으면 null, 있으면 JSON 문자열이어야 한다(아니면 예외 → 전체 null). */
    private fun text(root: JsonNode, name: String): String? {
        val node = root.get(name) ?: return null
        require(node.isString) { "type" }
        return node.asString()
    }

    private fun date(value: String): LocalDate {
        require(DATE_TEXT.matches(value)) { "date format" }
        return LocalDate.parse(value, DATE)
    }
}

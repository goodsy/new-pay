package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 사업자 목록 검색 본문 엄격 해석(BUSINESS-PROFILE-001 §6.2 — MASTER `BusinessSearchRequestParser`와 같은 규칙의 자료형 부분): JSON 객체 하나, **모든 필드 선택**, 알려진 필드만,
 * 중복 key·뒤따르는 토큰 거부, 자료형 강제 변환 없음(문자열 필드는 JSON 문자열, `limit`은 JSON 정수, `null` 거부 — 기존 `MerchantSearchBodyParser`와 같다). 값의 **형식**은 Use Case가 본다.
 * **`registrationNumber` 필드는 없다**(번호 검색은 번호 조회 endpoint만 — C-6, 보내면 모르는 필드). 실패하면 null(감사 없는 400 — 목록은 감사 대상이 아니다).
 */
object BusinessSearchBodyParser {

    private const val LIMIT = "limit"
    private val TEXT_FIELDS = setOf("displayNamePrefix", "businessType", "registrationStatus", "createdFrom", "createdTo", "after")

    private val strict: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parse(body: ByteArray): BusinessSearchCriteria? {
        val root = try {
            strict.readTree(body)
        } catch (e: JacksonException) {
            null
        }
        if (root == null || !root.isObject) return null
        val names = root.propertyNames().toList()
        if (names.any { it != LIMIT && it !in TEXT_FIELDS }) return null
        if (names.any { it in TEXT_FIELDS && !root.get(it).isString }) return null
        val limit = root.get(LIMIT)
        if (limit != null && !(limit.isIntegralNumber && limit.canConvertToInt())) return null
        fun text(name: String): String? = root.get(name)?.asString()
        return BusinessSearchCriteria(
            text("displayNamePrefix"), text("businessType"), text("registrationStatus"), text("createdFrom"), text("createdTo"), text("after"), limit?.asInt(),
        )
    }

    /** 번호 조회 본문: 정확히 `{"registrationNumber":"<문자열>"}`(형식 10자리는 Use Case). 그 밖 모두 null — 단일 목적 endpoint라 모든 실패가 시도(감사 `brn=INVALID`). */
    fun lookupNumber(body: ByteArray): String? {
        val parsed: JsonNode? = try {
            strict.readTree(body)
        } catch (e: JacksonException) {
            null
        }
        val root = parsed ?: return null
        if (!root.isObject || root.propertyNames().toSet() != setOf("registrationNumber")) return null
        val node = root.get("registrationNumber")
        return if (node.isString) node.asString() else null
    }
}

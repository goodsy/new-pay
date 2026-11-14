package com.paycore.admin.merchantops.adapter.web

import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 검색 본문 엄격 해석(MERCHANT-OPS-001 §4.1·§5.2, Q-MOPS-07 — MASTER `MerchantSearchRequestParser`와 같은 규칙의 자료형 부분): JSON 객체 하나, 알려진 필드만, 중복 key·뒤따르는
 * 토큰 거부, 자료형 강제 변환 없음(문자열 필드는 JSON 문자열, `limit`은 JSON 정수, `null` 거부). 값의 **형식**(ID 모양·10자리·범위)은 Use Case가 검사한다(기존 감사 순서 유지).
 *
 * 해석 실패면 [Parsed.Malformed]이고 **등록번호 검색 시도 여부**만 넘긴다(본문 원문·값은 넘기지 않는다 — 감사·로그·응답 비노출).
 * 시도 판정 규칙(가장 좁은 안전 규칙): 본문을 앞에서부터 토큰으로 읽어(중복 key 허용, 첫 문법 오류에서 멈춤) **첫 최상위 JSON 객체의 직속 key 중 `registrationNumber`가 나오면**
 * 시도다(값의 자료형·`null`·뒤의 오류와 무관, key의 `\u` escape는 해석된 이름으로 비교). 그 밖(최상위가 객체가 아님, 중첩 객체 안의 key, 대소문자가 다른 key, 그 key에 닿기 전의
 * 문법 오류, 첫 객체 뒤에 붙은 내용)은 시도가 아니다 — 어떤 해석으로도 등록번호 검색 조건이 될 수 없는 본문이기 때문이다.
 */
object MerchantSearchBodyParser {

    sealed interface Parsed {
        data class Criteria(val criteria: MerchantSearchCriteria) : Parsed

        data class Malformed(val registrationNumberAttempt: Boolean) : Parsed
    }

    private const val REGISTRATION_NUMBER = "registrationNumber"
    private const val LIMIT = "limit"

    /** MERCHANT-CODE-001 MC-2 코드 검색(JSON 문자열만 — 형식·단일 기준 검사는 Use Case). */
    private const val MERCHANT_CODE = "merchantCode"
    private val TEXT_FIELDS = setOf("merchantId", "businessId", "lifecycle", REGISTRATION_NUMBER, "after", MERCHANT_CODE)

    private val strict: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
    private val scanner: JsonMapper = JsonMapper.builder().build()

    fun parse(body: ByteArray): Parsed {
        val root = try {
            strict.readTree(body)
        } catch (e: JacksonException) {
            null
        }
        return criteria(root)?.let { Parsed.Criteria(it) } ?: Parsed.Malformed(namesRegistrationNumber(body))
    }

    private fun criteria(root: JsonNode?): MerchantSearchCriteria? {
        if (root == null || !root.isObject) return null
        val names = root.propertyNames().toList()
        if (names.any { it != LIMIT && it !in TEXT_FIELDS }) return null
        if (names.any { it in TEXT_FIELDS && !root.get(it).isString }) return null
        val limit = root.get(LIMIT)
        if (limit != null && !(limit.isIntegralNumber && limit.canConvertToInt())) return null
        fun text(name: String): String? = root.get(name)?.asString()
        return MerchantSearchCriteria(text("merchantId"), text("businessId"), text("lifecycle"), text(REGISTRATION_NUMBER), text("after"), limit?.asInt(), text(MERCHANT_CODE))
    }

    /** 첫 최상위 객체의 직속 key에 `registrationNumber`가 있는가(문법 오류 지점까지). */
    internal fun namesRegistrationNumber(body: ByteArray): Boolean = try {
        scanner.createParser(body).use { p ->
            if (p.nextToken() != JsonToken.START_OBJECT) return false
            var depth = 1
            while (depth > 0) {
                when (p.nextToken() ?: return false) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> depth++
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                    JsonToken.PROPERTY_NAME -> if (depth == 1 && p.currentName() == REGISTRATION_NUMBER) return true
                    else -> Unit
                }
            }
            false
        }
    } catch (e: JacksonException) {
        false
    }
}

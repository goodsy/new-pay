package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/**
 * 사업자등록번호 조회 본문(BUSINESS-PROFILE-001 §6.3 — 합친 MERCHANT-BIZ-LOOKUP-001): 정확히 `{"registrationNumber":"<10 ASCII 숫자>"}`. 중복 key·모르는 필드·비문자열·
 * 뒤따르는 토큰·하이픈 등 다른 표기(정규화 없음 — D-MD-008) → null(400 — 값 미반환, oracle 방어 D-MOPS-003). 번호는 POST 본문으로만 온다(URL·접근 로그에 없음 — C-6).
 */
object BusinessLookupRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parse(body: ByteArray): BusinessRegistrationNumber? = runCatching {
        val root = mapper.readTree(body)
        if (root == null || !root.isObject || root.propertyNames().toSet() != setOf("registrationNumber")) return null
        val node = root.get("registrationNumber")
        if (!node.isString || BusinessRegistrationNumber.errorOf(node.asString()) != null) return null
        BusinessRegistrationNumber(node.asString())
    }.getOrNull()
}

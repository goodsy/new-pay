package com.paycore.admin.financialops.adapter.web

import com.paycore.admin.financialops.application.provided.AcquisitionFileCursor
import com.paycore.admin.financialops.application.provided.AcquisitionFileSearchCriteria
import com.paycore.admin.financialops.application.provided.AcquisitionRecordSearchCriteria
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 화면 매입 현황 검색 본문 엄격 해석(ACQ-VIEW-001 §4.6 — FINANCIAL `AcquisitionSearchRequestParser`와 같은 자료형 규칙의 자료형 부분): JSON 객체 하나, 알려진 필드만, 중복 key·
 * 뒤따르는 토큰 거부, 자료형 강제 변환 없음(문자열 필드는 JSON 문자열, `needsAttention`은 JSON 불린, `limit`·`afterSeq`는 JSON 정수, `after`는 정확히 두 문자열 키 객체, `null` 거부).
 * 값의 **형식**(날짜·파일 ID·값 집합·범위·기간)은 Use Case가 FINANCIAL 호출 전에 검사한다. 해석 실패면 null(400 `INVALID_INPUT` — 본문 원문·필드 이름은 응답·로그에 없다).
 */
object AcquisitionSearchBodyParser {

    private val strict: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val FILE_TEXT = setOf("businessDateFrom", "businessDateTo", "fileId", "acquirerCode", "environment", "transmissionState", "receiptState", "resultState")
    private const val NEEDS_ATTENTION = "needsAttention"
    private const val AFTER = "after"
    private const val LIMIT = "limit"
    private val CURSOR = setOf("businessDate", "fileId")

    fun files(body: ByteArray): AcquisitionFileSearchCriteria? {
        val root = tree(body) ?: return null
        val names = root.propertyNames().toList()
        if (names.any { it !in FILE_TEXT && it != NEEDS_ATTENTION && it != AFTER && it != LIMIT }) return null
        if (names.any { it in FILE_TEXT && !root.get(it).isString }) return null
        val needsAttention = root.get(NEEDS_ATTENTION)?.let { if (it.isBoolean) it.asBoolean() else return null }
        val after = root.get(AFTER)?.let { node ->
            if (!node.isObject || node.propertyNames().toSet() != CURSOR || CURSOR.any { !node.get(it).isString }) return null
            AcquisitionFileCursor(node.get("businessDate").asString(), node.get("fileId").asString())
        }
        val limit = int(root, LIMIT) ?: if (root.has(LIMIT)) return null else null
        fun text(name: String): String? = root.get(name)?.asString()
        return AcquisitionFileSearchCriteria(
            text("businessDateFrom"), text("businessDateTo"), text("fileId"), text("acquirerCode"), text("environment"), text("transmissionState"),
            text("receiptState"), text("resultState"), needsAttention, after, limit,
        )
    }

    fun records(body: ByteArray): AcquisitionRecordSearchCriteria? {
        val root = tree(body) ?: return null
        val names = root.propertyNames().toList()
        if (names.any { it !in setOf("status", "afterSeq", LIMIT) }) return null
        val status = root.get("status")?.let { if (it.isString) it.asString() else return null }
        val afterSeq = int(root, "afterSeq") ?: if (root.has("afterSeq")) return null else null
        val limit = int(root, LIMIT) ?: if (root.has(LIMIT)) return null else null
        return AcquisitionRecordSearchCriteria(status, afterSeq, limit)
    }

    private fun tree(body: ByteArray): JsonNode? {
        val root = try {
            strict.readTree(body)
        } catch (e: JacksonException) {
            null
        }
        return root?.takeIf { it.isObject }
    }

    /** 있으면 JSON 정수(int 범위)일 때만 값, 아니면 null(호출자가 "있는데 null"을 거부로 본다). */
    private fun int(root: JsonNode, name: String): Int? = root.get(name)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()
}

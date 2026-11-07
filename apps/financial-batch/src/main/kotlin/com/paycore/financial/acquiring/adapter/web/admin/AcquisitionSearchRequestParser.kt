package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.acquiring.application.provided.AcquisitionFileCursor
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * 매입 현황 검색 본문 엄격 해석(ACQ-VIEW-001 §3.3 — TXN-OPS-001 §3.1과 같은 자료형 규칙): JSON 객체 하나, 알려진 키만, 중복 key·뒤따르는 토큰·`null`·자료형 강제 변환 거부.
 * 하나라도 어긋나면 null(400 `REQUEST_MALFORMED` — 어느 필드인지·입력 값을 응답·로그에 넣지 않는다). 모드·기간 불변식은 조건 타입 생성자가 다시 확인한다(위반 → null).
 */
object AcquisitionSearchRequestParser {

    /** 파일 ID 문법(파일 계약 v1 — `AcquisitionFileId`와 같은 모양). 경로 변수도 이 검사 뒤에만 조회한다. */
    val FILE_ID = Regex("^ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}$")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val FILE_FIELDS = setOf(
        "businessDateFrom", "businessDateTo", "fileId", "acquirerCode", "environment", "transmissionState", "receiptState", "resultState", "needsAttention", "after", "limit",
    )
    private val RECORD_FIELDS = setOf("status", "afterSeq", "limit")
    private val CURSOR_FIELDS = setOf("businessDate", "fileId")
    private val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val ACQUIRER_CODE = Regex("^[A-Z0-9]{1,32}$")
    private val ENVIRONMENT = Regex("^(TEST|LIVE)$")
    private val TRANSMISSION = Regex("^(SEALED|TRANSMITTING|TRANSMITTED|TRANSMISSION_UNKNOWN|TRANSMISSION_QUARANTINED|TRANSMISSION_WITHHELD)$")
    private val RECEIPT = Regex("^(NONE|RECEIVED|FILE_REJECTED|RECEIPT_CONFLICT)$")
    private val RESULT = Regex("^(AWAITING|COMPLETE|DEADLINE_PASSED)$")
    private val RECORD_STATUS = Regex("^(AWAITING|ACCEPTED|REJECTED|FILE_REJECTED|UNKNOWN|CONFLICT)$")

    fun files(body: ByteArray): AcquisitionFileSearchCriteria? = runCatching {
        val root = objectOf(body, FILE_FIELDS) ?: return null
        AcquisitionFileSearchCriteria(
            businessDateFrom = date(root, "businessDateFrom"),
            businessDateTo = date(root, "businessDateTo"),
            fileId = text(root, "fileId", FILE_ID),
            acquirerCode = text(root, "acquirerCode", ACQUIRER_CODE),
            environment = text(root, "environment", ENVIRONMENT),
            transmissionState = text(root, "transmissionState", TRANSMISSION),
            receiptState = text(root, "receiptState", RECEIPT),
            resultState = text(root, "resultState", RESULT),
            needsAttention = root.get("needsAttention")?.let { require(it.isBoolean && it.booleanValue()) { "needsAttention" }; true } ?: false,
            after = root.get("after")?.let(::cursor),
            limit = int(root, "limit") ?: AcquisitionFileSearchCriteria.DEFAULT_LIMIT,
        )
    }.getOrNull()

    fun records(body: ByteArray): AcquisitionRecordSearchCriteria? = runCatching {
        val root = objectOf(body, RECORD_FIELDS) ?: return null
        AcquisitionRecordSearchCriteria(
            status = text(root, "status", RECORD_STATUS),
            afterSeq = int(root, "afterSeq") ?: 0,
            limit = int(root, "limit") ?: AcquisitionRecordSearchCriteria.DEFAULT_LIMIT,
        )
    }.getOrNull()

    private fun objectOf(body: ByteArray, fields: Set<String>): JsonNode? {
        val root = mapper.readTree(body)
        return root?.takeIf { it.isObject && it.propertyNames().all { name -> name in fields } }
    }

    private fun cursor(node: JsonNode): AcquisitionFileCursor {
        require(node.isObject && node.propertyNames().toSet() == CURSOR_FIELDS) { "cursor" }
        return AcquisitionFileCursor(requireNotNull(date(node, "businessDate")), requireNotNull(text(node, "fileId", FILE_ID)))
    }

    /** 필드가 없으면 null, 있으면 `yyyy-MM-dd` 문자열이고 실제 달력일이어야 한다(ISO 엄격 해석 — 2월 30일 거부). */
    private fun date(root: JsonNode, name: String): LocalDate? = text(root, name, DATE)?.let(LocalDate::parse)

    /** 필드가 없으면 null, 있으면 문자열·형식 일치여야 한다(아니면 예외 → 전체 null). */
    private fun text(root: JsonNode, name: String, format: Regex): String? {
        val node = root.get(name) ?: return null
        require(node.isString) { "type" }
        return node.asString().also { require(format.matches(it)) { "format" } }
    }

    /** 필드가 없으면 null, 있으면 JSON 정수(int 범위)여야 한다 — 문자열 숫자·소수 거부. */
    private fun int(root: JsonNode, name: String): Int? {
        val node = root.get(name) ?: return null
        require(node.isIntegralNumber && node.canConvertToInt()) { "type" }
        return node.asInt()
    }
}

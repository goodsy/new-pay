package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.ReacquisitionCommand
import com.paycore.financial.acquiring.domain.ReacquisitionItem
import com.paycore.financial.acquiring.domain.ReacquisitionTargetStatus
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 재매입 실행 명령 본문 엄격 해석(ACQ-ADMIN-001 B1 — 명세 §4, ADMIN-PAYMENT-OPS-001 §4.3과 같은 자료형 규칙): JSON 객체 하나, **모든 필드 필수**, 알려진 키만,
 * 중복 key·뒤따르는 토큰·`null`·자료형 강제 변환(문자열 숫자·소수) 거부, 시각은 `yyyy-MM-ddTHH:mm:ss.SSSZ`만. 하나라도 어긋나면 null(400 `REQUEST_MALFORMED` — 어느 필드인지·
 * 입력 값을 응답·로그에 넣지 않는다). 업무 불변식(항목 순서·수·기한 순서·ID 문법)은 [ReacquisitionCommand] 생성자가 다시 확인한다(위반 → null).
 * `correlationId`는 추적용이다(내용 hash에 들어가지 않는다 — 재전송이 다른 상관 ID를 써도 같은 명령).
 */
object ReacquisitionCommandParser {

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private val FIELDS = setOf(
        "approvalId", "originalFileId", "items", "requestedBy", "approvedBy", "reasonCode", "evidenceRef", "approvedAt", "executeBefore", "correlationId",
    )
    private val ITEM_FIELDS = setOf("seq", "expectedGeneration", "expectedStatus")
    private val TARGET_FIELDS = setOf("seqs")
    private val TIME = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val CORRELATION = Regex("^[A-Za-z0-9._:-]{1,64}$")
    private val STATUS = Regex("^(REJECTED|FILE_REJECTED|UNKNOWN)$")

    /** 승인 ID 문법(ADMIN 승인 ID — 경로 변수도 이 검사 뒤에만 조회한다). */
    val APPROVAL_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")

    fun command(body: ByteArray): ReacquisitionCommand? = runCatching {
        val root = mapper.readTree(body)
        require(root != null && root.isObject && root.propertyNames().toSet() == FIELDS) { "fields" }
        text(root, "correlationId", CORRELATION)
        val items = root.get("items")
        require(items.isArray) { "items" }
        ReacquisitionCommand(
            approvalId = text(root, "approvalId", APPROVAL_ID),
            originalFileId = AcquisitionFileId.parse(text(root, "originalFileId", AcquisitionSearchRequestParser.FILE_ID)),
            items = elements(items).map(::item),
            requestedBy = text(root, "requestedBy", APPROVAL_ID),
            approvedBy = text(root, "approvedBy", APPROVAL_ID),
            reasonCode = text(root, "reasonCode", Regex("^[A-Z0-9_]{1,32}$")),
            evidenceRef = text(root, "evidenceRef", Regex("^[A-Za-z0-9._:#/-]{1,128}$")),
            approvedAt = time(root, "approvedAt"),
            executeBefore = time(root, "executeBefore"),
        )
    }.getOrNull()

    /** 미리 보기 본문 `{"seqs":[1..50개 양의 정수, 중복 없음]}`. */
    fun seqs(body: ByteArray): List<Int>? = runCatching {
        val root = mapper.readTree(body)
        require(root != null && root.isObject && root.propertyNames().toSet() == TARGET_FIELDS) { "fields" }
        val seqs = root.get("seqs")
        require(seqs.isArray && seqs.size() in 1..MAX_ITEMS) { "seqs" }
        elements(seqs).map { require(it.isIntegralNumber && it.canConvertToInt() && it.asInt() >= 1) { "seq" }; it.asInt() }.also {
            require(it.toSet().size == it.size) { "duplicate" }
        }
    }.getOrNull()

    private fun elements(array: JsonNode): List<JsonNode> = (0 until array.size()).map { array.get(it) }

    private fun item(node: JsonNode): ReacquisitionItem {
        require(node.isObject && node.propertyNames().toSet() == ITEM_FIELDS) { "item" }
        return ReacquisitionItem(int(node, "seq"), int(node, "expectedGeneration"), ReacquisitionTargetStatus.valueOf(text(node, "expectedStatus", STATUS)))
    }

    private fun text(root: JsonNode, name: String, format: Regex): String {
        val node = requireNotNull(root.get(name)) { "missing" }
        require(node.isString) { "type" }
        return node.asString().also { require(format.matches(it)) { "format" } }
    }

    private fun int(root: JsonNode, name: String): Int {
        val node = requireNotNull(root.get(name)) { "missing" }
        require(node.isIntegralNumber && node.canConvertToInt()) { "type" }
        return node.asInt()
    }

    private fun time(root: JsonNode, name: String): Instant = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(text(root, name, TIME)))

    private const val MAX_ITEMS = 50
}

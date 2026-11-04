package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.provided.AdminPaymentCursor
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 관리자 거래 검색 본문 엄격 해석(TXN-OPS-001 §3.1 — 서명 필터가 넘긴 서명된 본문 바이트): JSON 객체 하나, 알려진 키만, 중복 key·뒤따르는 토큰·`null` 거부, 자료형 강제 변환 없음
 * (문자열 필드는 JSON 문자열, `limit`은 JSON 정수), 값 형식(시각 `…T…ss.SSSZ`·환경·ID·상태·limit 범위), 모드·기간 규칙([AdminPaymentSearchCriteria] 불변식 — I-3, 31일 Demo 상한).
 * 실패는 null 하나뿐이다(어느 필드인지·어떤 값인지 말하지 않는다 — 응답·로그에 요청 값 없음).
 */
object AdminPaymentSearchRequestParser {

    private val strict: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
    val PAYMENT_ID = Regex("^pay_$ULID$")
    private val TIME = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
    private val REF = Regex("^[A-Za-z0-9._:-]{1,64}$")
    private val ENVIRONMENTS = setOf("TEST", "LIVE")
    private val STATUSES = setOf("REQUESTED", "AUTHORIZING", "UNKNOWN", "AUTHORIZED", "FAILED", "EXPIRED")
    private val TEXT_FIELDS = setOf("createdFrom", "createdTo", "environment", "merchantAccountId", "merchantOrderId", "paymentId", "status")
    private val CURSOR_FIELDS = setOf("createdAt", "paymentId")

    fun parse(body: ByteArray): AdminPaymentSearchCriteria? = try {
        criteria(strict.readTree(body))
    } catch (e: RuntimeException) {
        // Jackson 해석 오류·잘못된 UTF-8·형식 불변식 위반(IllegalArgumentException) — 모두 같은 400.
        null
    }

    private fun criteria(root: JsonNode?): AdminPaymentSearchCriteria? {
        if (root == null || !root.isObject) return null
        val names = root.propertyNames().toSet()
        if (names.any { it !in TEXT_FIELDS && it != "after" && it != "limit" }) return null
        if (names.any { it in TEXT_FIELDS && !root.get(it).isString }) return null
        fun text(name: String, format: (String) -> Boolean): String? = root.get(name)?.asString()?.also { require(format(it)) }
        val limit = root.get("limit")?.let {
            require(it.isIntegralNumber && it.canConvertToInt()) { "limit" }
            it.asInt()
        }
        return AdminPaymentSearchCriteria(
            createdFrom = text("createdFrom", TIME::matches)?.let(::instant),
            createdTo = text("createdTo", TIME::matches)?.let(::instant),
            environment = text("environment") { it in ENVIRONMENTS },
            merchantAccountId = text("merchantAccountId", REF::matches),
            merchantOrderId = text("merchantOrderId", REF::matches),
            paymentId = text("paymentId", PAYMENT_ID::matches),
            status = text("status") { it in STATUSES },
            after = root.get("after")?.let(::cursor),
            limit = limit ?: AdminPaymentSearchCriteria.DEFAULT_LIMIT,
        )
    }

    private fun cursor(node: JsonNode): AdminPaymentCursor {
        require(node.isObject && node.propertyNames().toSet() == CURSOR_FIELDS && CURSOR_FIELDS.all { node.get(it).isString }) { "cursor" }
        val at = node.get("createdAt").asString().also { require(TIME.matches(it)) }
        val id = node.get("paymentId").asString().also { require(PAYMENT_ID.matches(it)) }
        return AdminPaymentCursor(instant(at), id)
    }

    private fun instant(value: String): Instant = try {
        Instant.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("time", e)
    }
}

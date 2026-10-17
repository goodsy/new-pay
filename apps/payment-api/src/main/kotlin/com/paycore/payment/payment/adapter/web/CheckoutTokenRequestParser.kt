package com.paycore.payment.payment.adapter.web

import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 형식 검증을 통과한 결제창 토큰 요청 본문(원문 문자열 — URL 문법은 Use Case가 domain 규칙으로 판정한다, AUTHN-001 §5.1·§8.1). */
data class CheckoutTokenBody(val successUrl: String, val failUrl: String) {
    override fun toString(): String = "CheckoutTokenBody(****)"
}

/**
 * 결제창 토큰 요청 엄격 파서(AUTHN-001 §5.1, AN-03 — [CreatePaymentRequestParser]와 같은 방식): 본문 16 KiB 이하·엄격 UTF-8·BOM 없음, Jackson 트리(중복 키·끝 뒤 토큰
 * 거절), 최상위 객체, 닫힌 필드 2개(`successUrl`·`failUrl`) 모두 필수 문자열(null·숫자·중첩 금지), 짝 없는 서로게이트 거절. 위반은 400 INVALID_REQUEST(알려진 필드 이름만,
 * 값 echo 없음). `Idempotency-Key` 헤더가 하나라도 있으면 400(field `Idempotency-Key`, AN-02·n-8).
 */
object CheckoutTokenRequestParser {
    const val MAX_BODY_BYTES = 16 * 1024
    private val FIELDS = listOf("successUrl", "failUrl")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun rejectIdempotencyKey(values: List<String>) {
        if (values.isNotEmpty()) throw RequestRejected(ApiError(ApiErrorCode.INVALID_REQUEST, field = IDEMPOTENCY_KEY_HEADER))
    }

    fun parse(body: ByteArray): CheckoutTokenBody {
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw invalid()
        val text = strictUtf8(body) ?: throw invalid()
        if (text.startsWith('\uFEFF')) throw invalid()
        val root = try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw invalid()
        }
        if (root == null || !root.isObject) throw invalid()
        val names = root.properties().map { it.key }
        if (names.any { it !in FIELDS }) throw invalid()
        val values = FIELDS.map { field ->
            val node = root.get(field) ?: throw invalid(field)
            if (!node.isString) throw invalid(field)
            node.stringValue().takeIf(::isWellFormed) ?: throw invalid(field)
        }
        return CheckoutTokenBody(values[0], values[1])
    }

    private fun isWellFormed(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return false
            i++
        }
        return true
    }

    private fun strictUtf8(body: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun invalid(field: String? = null) = RequestRejected(ApiError(ApiErrorCode.INVALID_REQUEST, field = field))

    private const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
}

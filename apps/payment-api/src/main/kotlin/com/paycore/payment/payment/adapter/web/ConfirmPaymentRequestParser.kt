package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.Utf8Text
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 형식 검증을 통과한 confirm 본문(AUTH-001 §4.1). 값 대조는 Use Case가 한다. */
data class ParsedConfirmBody(val authenticationRef: String, val merchantOrderId: String, val money: Money) {
    override fun toString(): String = "ParsedConfirmBody(merchantOrderId=$merchantOrderId)"
}

/**
 * confirm 본문 엄격 파서(AUTH-001 §4.1 — CreatePayment 파서와 같은 관례: 엄격 UTF-8·BOM 거절·중복 키·끝 뒤 토큰 거절, 닫힌 필드 4개, null·중첩 거절). 첫 위반 하나, 값 echo 없음.
 * `authenticationRef`는 `aref_` + base64url 22자(AN-22), `merchantOrderId`는 D-CPC-002 형식, amount는 minor 정수 ≥ 1, currency는 대문자 3자.
 */
object ConfirmPaymentRequestParser {
    const val MAX_BODY_BYTES = 4 * 1024

    private val FIELDS = setOf("authenticationRef", "merchantOrderId", "amount", "currency")
    private val AUTHENTICATION_REF = Regex("^aref_[A-Za-z0-9_-]{22}$")
    private val ORDER_ID = Regex("^[A-Za-z0-9\\-_.:]{1,64}$")
    private val PAYMENT_ID = Regex("^[!-~]{1,64}$")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** 경로의 paymentId가 서버 식별자처럼 생겼는가(아니면 404 — 존재 비노출, AN-01과 같음). */
    fun isPaymentIdLike(value: String): Boolean = PAYMENT_ID.matches(value)

    fun parse(body: ByteArray): ParsedConfirmBody {
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw invalid()
        val text = strictUtf8(body) ?: throw invalid()
        if (text.startsWith('﻿')) throw invalid()
        val root = try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw invalid()
        }
        if (root == null || !root.isObject) throw invalid()
        val props = root.properties().toList()
        if (props.any { it.key !in FIELDS } || props.any { it.value.isNull || it.value.isObject || it.value.isArray }) throw invalid()
        val ref = string(root, "authenticationRef").takeIf { AUTHENTICATION_REF.matches(it) } ?: throw invalid("authenticationRef")
        val orderId = string(root, "merchantOrderId").takeIf { ORDER_ID.matches(it) } ?: throw invalid("merchantOrderId")
        val amountNode = root.get("amount")
        val amount = amountNode?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        if (amount == null || amount < 1) throw RequestRejected(ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount"))
        val currency = root.get("currency")?.takeIf { it.isString }?.stringValue()
        if (currency == null || currency.length != 3 || !currency.all { it in 'A'..'Z' }) throw RequestRejected(ApiError(ApiErrorCode.INVALID_CURRENCY, field = "currency"))
        return ParsedConfirmBody(ref, orderId, Money(amount, CurrencyCode(currency)))
    }

    private fun string(root: JsonNode, field: String): String {
        val node = root.get(field) ?: throw invalid(field)
        if (!node.isString) throw invalid(field)
        val value = node.stringValue()
        if (!Utf8Text.isWellFormed(value)) throw invalid(field)
        return value
    }

    private fun strictUtf8(body: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun invalid(field: String? = null) = RequestRejected(ApiError(ApiErrorCode.INVALID_REQUEST, field = field))
}

package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.MerchantCancelId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.Utf8Text
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 형식 검증을 통과한 취소 본문(CANCEL-001 §4.1). 값 대조(금액·통화·잔액)는 Use Case가 한다. */
data class ParsedCancelBody(val merchantCancelId: MerchantCancelId, val money: Money)

/**
 * 취소 본문 엄격 파서(CANCEL-001 §4.1 CN-01 — confirm·CreatePayment 파서와 같은 관례: 엄격 UTF-8·BOM 거절·중복 키·끝 뒤 토큰 거절, 닫힌 필드 3개, null·중첩 거절). 첫 위반 하나, 값 echo 없음.
 * `merchantCancelId`는 D-CPC-002 형식(1..64 `[A-Za-z0-9-_.:]` — 넘는 입력은 400, rev 0.3 m-3), amount는 minor 정수 ≥ 1, currency는 대문자 3자. 가맹점 사유 문구는 받지 않는다(모르는 필드 400).
 */
object CancelPaymentRequestParser {
    const val MAX_BODY_BYTES = 4 * 1024

    private val FIELDS = setOf("merchantCancelId", "amount", "currency")
    private val MERCHANT_CANCEL_ID = Regex("^[A-Za-z0-9\\-_.:]{1,64}$")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun parse(body: ByteArray): ParsedCancelBody {
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
        val idNode = root.get("merchantCancelId") ?: throw invalid("merchantCancelId")
        val id = idNode.takeIf { it.isString }?.stringValue()?.takeIf { Utf8Text.isWellFormed(it) && MERCHANT_CANCEL_ID.matches(it) } ?: throw invalid("merchantCancelId")
        val amount = root.get("amount")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        if (amount == null || amount < 1) throw RequestRejected(ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount"))
        val currency = root.get("currency")?.takeIf { it.isString }?.stringValue()
        if (currency == null || currency.length != 3 || !currency.all { it in 'A'..'Z' }) throw RequestRejected(ApiError(ApiErrorCode.INVALID_CURRENCY, field = "currency"))
        return ParsedCancelBody(MerchantCancelId(id), Money(amount, CurrencyCode(currency)))
    }

    private fun strictUtf8(body: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun invalid(field: String? = null) = RequestRejected(ApiError(ApiErrorCode.INVALID_REQUEST, field = field))
}

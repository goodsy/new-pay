package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.LineSafeText
import com.paycore.payment.payment.application.provided.OrderNameRule
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.IdempotencyKey
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantOrderId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentMethod
import com.paycore.payment.payment.domain.Utf8Text
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 형식 검증을 통과한 본문 값(CP-CONTRACT-001 §3). */
data class ParsedCreatePaymentBody(
    val merchantAccountId: MerchantAccountId,
    val merchantSiteId: MerchantSiteId,
    val merchantOrderId: MerchantOrderId,
    val money: Money,
    val paymentMethod: PaymentMethod,
    val orderName: String?,
)

/** 형식 위반(400). 첫 위반 하나만 보고한다(명세 §4.3). */
class RequestRejected(val error: ApiError) : RuntimeException(null, null, false, false)

/**
 * CreatePayment 요청 엄격 파서(CP-CONTRACT-001 §3.1·D-CPC-009, CP-API-001 명세 §4.2–§4.4). Spring 메시지 변환·데이터 바인딩을 쓰지 않는다:
 *
 * 1. 본문 바이트를 **엄격 UTF-8**(malformed REPORT)로 문자열화한다. 잘못된 UTF-8·BOM·빈 본문은 거절(UTF-16/32 자동 감지를 막는다).
 * 2. Jackson 트리로 읽되 중복 키·끝 뒤 토큰을 거절한다. 최상위는 객체, 값은 스칼라만.
 * 3. 필드 이름은 닫힌 집합 7개(모르는 이름은 echo하지 않고 거절), `null`은 거절(선택 필드는 생략만).
 * 4. amount: JSON 정수 토큰만(소수·지수·문자열·`+`·선행 0·Long 초과·0 이하 → INVALID_AMOUNT). `+`·선행 0은 JSON 문법 오류라 분류만 따로 한다.
 * 5. 모든 문자열 값: 짝 없는 서로게이트(`\uD800` escape) 거절. 치환하지 않는다.
 * 첫 위반의 순서 = 아래 필드 표 순서. 오류에는 알려진 필드 이름만 담고 값을 담지 않는다.
 */
object CreatePaymentRequestParser {
    const val MAX_BODY_BYTES = 16 * 1024
    private const val MAX_ID_LENGTH = 64

    private val FIELDS = listOf("merchantAccountId", "merchantSiteId", "merchantOrderId", "amount", "currency", "paymentMethod", "orderName")

    private val mapper: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /** 분류 전용: `+`·선행 0 숫자 표기만 더 허용한다(값을 받는 데 쓰지 않는다). */
    private val lenientNumbers: JsonMapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS, JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    fun idempotencyKey(values: List<String>): IdempotencyKey {
        val single = values.singleOrNull()?.takeIf(MerchantIdFormat::isValid)
            ?: throw RequestRejected(ApiError(ApiErrorCode.INVALID_IDEMPOTENCY_KEY, field = "Idempotency-Key"))
        return IdempotencyKey(single)
    }

    fun parse(body: ByteArray): ParsedCreatePaymentBody {
        if (body.isEmpty() || body.size > MAX_BODY_BYTES) throw invalid()
        val text = strictUtf8(body) ?: throw invalid()
        if (text.startsWith('﻿')) throw invalid()
        val root = try {
            mapper.readTree(text)
        } catch (e: JacksonException) {
            throw syntaxError(text)
        }
        return validate(root, amountSyntaxInvalid = false)
    }

    /**
     * 명세 §4.3 순서대로 검사한다: 구조(객체·닫힌 필드 집합·null·중첩) → merchantAccountId → merchantSiteId → merchantOrderId → amount → currency
     * → paymentMethod → orderName. [amountSyntaxInvalid]이면 amount 차례에서 `INVALID_AMOUNT`다(`+`·선행 0 — 앞선 구조·필드 위반이 먼저 보고된다, review n-5).
     */
    private fun validate(root: JsonNode?, amountSyntaxInvalid: Boolean): ParsedCreatePaymentBody {
        if (root == null || !root.isObject) throw invalid()
        val properties = root.properties().toList()
        if (properties.any { it.key !in FIELDS }) throw invalid()
        if (properties.any { it.value.isNull || it.value.isObject || it.value.isArray }) throw invalid()

        val mid = requiredString(root, "merchantAccountId").also { requireIdLike(it, "merchantAccountId") }
        val site = requiredString(root, "merchantSiteId").also { requireIdLike(it, "merchantSiteId") }
        val orderId = requiredString(root, "merchantOrderId").takeIf(MerchantIdFormat::isValid) ?: throw invalid("merchantOrderId")
        if (amountSyntaxInvalid) throw RequestRejected(ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount"))
        val amount = amount(root)
        val currency = currency(root)
        val method = requiredString(root, "paymentMethod").let { v ->
            PaymentMethod.entries.firstOrNull { it.name == v } ?: throw invalid("paymentMethod")
        }
        val orderName = root.get("orderName")?.let { node ->
            val value = node.takeIf { it.isString }?.stringValue() ?: throw invalid("orderName")
            value.takeIf(OrderNameRule::isValid) ?: throw invalid("orderName")
        }
        return ParsedCreatePaymentBody(MerchantAccountId(mid), MerchantSiteId(site), MerchantOrderId(orderId), Money(amount, currency), method, orderName)
    }

    /**
     * JSON 문법 오류. `+1`·`010`처럼 숫자 표기만 문법 밖인 경우는 계약 §3.1대로 `INVALID_AMOUNT`로 분류한다: 그 두 표기만 허용하는 관대한 파서로
     * 다시 읽어 성공하면 같은 순서 검사([validate])를 돌려 앞선 구조·필드 위반을 먼저 보고하고, amount 차례에서 `INVALID_AMOUNT`를 낸다(숫자 필드는
     * amount뿐 — 다른 필드의 숫자 표기는 그 필드 형식 위반이다). 관대한 결과로 값을 받지는 않는다.
     */
    private fun syntaxError(text: String): RequestRejected {
        val lenientRoot = try {
            lenientNumbers.readTree(text)
        } catch (e: JacksonException) {
            return invalid()
        }
        return try {
            validate(lenientRoot, amountSyntaxInvalid = amountHasNonStandardNumber(text))
            invalid()
        } catch (e: RequestRejected) {
            e
        }
    }

    /**
     * 최상위 `amount` 값의 **원문 표기**가 JSON 숫자 문법 밖(`+`·선행 0)인가. 문법 오류가 다른 필드(예: currency 자리의 `+1`)에 있을 때 정상
     * amount를 INVALID_AMOUNT로 오분류하지 않기 위해 트리 값이 아니라 토큰 원문을 본다.
     */
    private fun amountHasNonStandardNumber(text: String): Boolean = try {
        lenientNumbers.createParser(text).use { p ->
            var depth = 0
            var token = p.nextToken()
            while (token != null) {
                when (token) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> depth++
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                    JsonToken.PROPERTY_NAME -> if (depth == 1 && p.currentName() == "amount") {
                        val value = p.nextToken()
                        if (value != JsonToken.VALUE_NUMBER_INT && value != JsonToken.VALUE_NUMBER_FLOAT) return false
                        val start = p.currentTokenLocation().charOffset.toInt()
                        val raw = text.substring(start).takeWhile { it in NUMBER_CHARS }
                        return !STANDARD_JSON_NUMBER.matches(raw)
                    }
                    else -> Unit
                }
                token = p.nextToken()
            }
            false
        }
    } catch (e: JacksonException) {
        false
    }

    private const val NUMBER_CHARS = "+-0123456789.eE"
    private val STANDARD_JSON_NUMBER = Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$")

    private fun requiredString(root: JsonNode, field: String): String {
        val node = root.get(field) ?: throw invalid(field)
        if (!node.isString) throw invalid(field)
        val value = node.stringValue()
        if (!Utf8Text.isWellFormed(value)) throw invalid(field)
        return value
    }

    /**
     * MID·Site: 비어 있지 않음·64자 이하(영속 컬럼)·거절 문자 없음([LineSafeText] — 제어문자·줄 구분자·bidi 제어, review round2 m-2: 이 값은
     * 로그·eligibility 판정 로그에 남는다). 형식(접두·ULID)은 PAYMENT가 강제하지 않는다 — 없는 Site는 eligibility 404.
     */
    private fun requireIdLike(value: String, field: String) {
        if (value.isEmpty() || value.length > MAX_ID_LENGTH || LineSafeText.hasForbiddenCharacter(value)) throw invalid(field)
    }

    private fun amount(root: JsonNode): Long {
        val node = root.get("amount") ?: throw RequestRejected(ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount"))
        val value = node.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        if (value == null || value < 1) throw RequestRejected(ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount"))
        return value
    }

    private fun currency(root: JsonNode): CurrencyCode {
        val node = root.get("currency")
        val value = node?.takeIf { it.isString }?.stringValue()
        if (value == null || value.length != 3 || !value.all { it in 'A'..'Z' }) {
            throw RequestRejected(ApiError(ApiErrorCode.INVALID_CURRENCY, field = "currency"))
        }
        return CurrencyCode(value)
    }

    private fun strictUtf8(body: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun invalid(field: String? = null) = RequestRejected(ApiError(ApiErrorCode.INVALID_REQUEST, field = field))

    /** D-CPC-002 형식(merchantOrderId·멱등키) — domain `MerchantRequestIdFormat`과 같은 규칙(web이 먼저 400을 만든다). */
    private object MerchantIdFormat {
        private val PATTERN = Regex("^[A-Za-z0-9\\-_.:]{1,64}$")

        fun isValid(value: String): Boolean = PATTERN.matches(value)
    }
}

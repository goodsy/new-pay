package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.application.provided.OrderNameRule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 엄격 요청 파서(CP-CONTRACT-001 §3·§3.1·D-CPC-009, CP-API-001 명세 §4.2–§4.4). 정상 본문은 [CreatePaymentRequests.valid], 위반은 의도가 이름에
 * 드러나는 명시 Fixture로 만든다(Test Fixture Policy — Invalid Fixture 의도 명시).
 */
class CreatePaymentRequestParserSpec : BehaviorSpec({

    fun rejected(body: String) = shouldThrow<RequestRejected> { CreatePaymentRequestParser.parse(body.toByteArray(Charsets.UTF_8)) }.error
    fun rejectedBytes(body: ByteArray) = shouldThrow<RequestRejected> { CreatePaymentRequestParser.parse(body) }.error

    Given("a valid body") {
        Then("all seven fields are read as given (no trim, no normalisation)") {
            val parsed = CreatePaymentRequestParser.parse(CreatePaymentRequests.valid(orderName = " Demo 상품 😀 ").toByteArray())
            parsed.merchantOrderId.value shouldBe CreatePaymentRequests.ORDER_ID
            parsed.money.amountMinor shouldBe 10_000L
            parsed.money.currency.value shouldBe "KRW"
            parsed.orderName shouldBe " Demo 상품 😀 "
        }
        Then("orderName may be omitted; field order and whitespace do not matter") {
            CreatePaymentRequestParser.parse(CreatePaymentRequests.valid(orderName = null).toByteArray()).orderName shouldBe null
            CreatePaymentRequestParser.parse(CreatePaymentRequests.reordered().toByteArray()).money.amountMinor shouldBe 10_000L
        }
        Then("amount up to Long.MAX_VALUE is exact (no floating point)") {
            CreatePaymentRequestParser.parse(CreatePaymentRequests.withRawAmount("9223372036854775807").toByteArray()).money.amountMinor shouldBe Long.MAX_VALUE
            CreatePaymentRequestParser.parse(CreatePaymentRequests.withRawAmount("9007199254740993").toByteArray()).money.amountMinor shouldBe 9_007_199_254_740_993L
        }
    }

    Given("amounts that are not exact positive JSON integers (D-CPC-009)") {
        listOf("1.0", "1e3", "1E3", "\"100\"", "+1", "010", "-0", "0", "-5", "9223372036854775808", "true", "null").forEach { raw ->
            Then("amount $raw is rejected") {
                val error = rejected(CreatePaymentRequests.withRawAmount(raw))
                // null은 계약 §3.1의 null 규칙(필수 필드 null = 형식 오류)이 먼저다.
                error shouldBe if (raw == "null") ApiError(ApiErrorCode.INVALID_REQUEST) else ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount")
            }
        }
    }

    Given("the closed field set and JSON strictness (contract §3.1)") {
        Then("an unknown field is rejected without echoing its name") {
            val error = rejected(CreatePaymentRequests.withExtraField("\"customerEmail\":\"x@example.com\""))
            error shouldBe ApiError(ApiErrorCode.INVALID_REQUEST)
        }
        Then("duplicate keys, null values, nested values, trailing tokens and non-objects are rejected") {
            rejected(CreatePaymentRequests.withExtraField("\"currency\":\"KRW\"")).code shouldBe ApiErrorCode.INVALID_REQUEST
            rejected(CreatePaymentRequests.valid(orderNameJson = "null")).code shouldBe ApiErrorCode.INVALID_REQUEST
            rejected(CreatePaymentRequests.valid(orderNameJson = "{\"a\":1}")).code shouldBe ApiErrorCode.INVALID_REQUEST
            rejected(CreatePaymentRequests.valid() + " {}").code shouldBe ApiErrorCode.INVALID_REQUEST
            // 문법 오류 분류용 재해석도 중복 키·끝 뒤 토큰을 받아 주지 않는다.
            rejected(CreatePaymentRequests.withRawAmount("+1") + " {}") shouldBe ApiError(ApiErrorCode.INVALID_REQUEST)
            rejected(CreatePaymentRequests.withRawAmount("+1").replace("}", ",\"amount\":1}")) shouldBe ApiError(ApiErrorCode.INVALID_REQUEST)
            rejected("[]").code shouldBe ApiErrorCode.INVALID_REQUEST
            rejected("").code shouldBe ApiErrorCode.INVALID_REQUEST
        }
        Then("a missing required field is reported by name") {
            rejected(CreatePaymentRequests.without("merchantSiteId")) shouldBe ApiError(ApiErrorCode.INVALID_REQUEST, field = "merchantSiteId")
            rejected(CreatePaymentRequests.without("amount")) shouldBe ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount")
        }
        Then("a body over 16 KiB is rejected") {
            rejected(CreatePaymentRequests.valid(orderName = "x".repeat(20_000))).code shouldBe ApiErrorCode.INVALID_REQUEST
        }
    }

    Given("byte-level encoding (UTF-8 only)") {
        Then("malformed UTF-8, a BOM and UTF-16 are rejected") {
            val valid = CreatePaymentRequests.valid().toByteArray(Charsets.UTF_8)
            rejectedBytes(valid.copyOf(valid.size - 1) + byteArrayOf(0xC3.toByte(), '}'.code.toByte())).code shouldBe ApiErrorCode.INVALID_REQUEST
            rejectedBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + valid).code shouldBe ApiErrorCode.INVALID_REQUEST
            rejectedBytes(CreatePaymentRequests.valid().toByteArray(Charsets.UTF_16)).code shouldBe ApiErrorCode.INVALID_REQUEST
        }
    }

    Given("unpaired surrogates in any string field (contract §3.1)") {
        listOf("merchantAccountId", "merchantSiteId", "orderName").forEach { field ->
            Then("a lone high surrogate escape in $field is rejected (never replaced with '?')") {
                rejected(CreatePaymentRequests.withEscapedString(field, "a\\uD800")) shouldBe ApiError(ApiErrorCode.INVALID_REQUEST, field = field)
            }
        }
        Then("a reversed pair is rejected; a proper pair (supplementary character) is accepted") {
            rejected(CreatePaymentRequests.withEscapedString("orderName", "\\uDE00\\uD83D")).field shouldBe "orderName"
            CreatePaymentRequestParser.parse(CreatePaymentRequests.withEscapedString("orderName", "\\uD83D\\uDE00").toByteArray()).orderName shouldBe "😀"
        }
        Then("merchantOrderId and the idempotency key are ASCII-only by format (D-CPC-002)") {
            rejected(CreatePaymentRequests.withEscapedString("merchantOrderId", "a\\uD800")).field shouldBe "merchantOrderId"
            shouldThrow<RequestRejected> { CreatePaymentRequestParser.idempotencyKey(listOf("k\uD800")) }
        }
    }

    Given("field formats") {
        Then("merchantOrderId outside [A-Za-z0-9-_.:]{1,64}, currency not upper-case ISO, unknown method") {
            rejected(CreatePaymentRequests.withEscapedString("merchantOrderId", "ord 1")).field shouldBe "merchantOrderId"
            rejected(CreatePaymentRequests.withEscapedString("merchantOrderId", "o".repeat(65))).field shouldBe "merchantOrderId"
            rejected(CreatePaymentRequests.withEscapedString("currency", "krw")) shouldBe ApiError(ApiErrorCode.INVALID_CURRENCY, field = "currency")
            rejected(CreatePaymentRequests.withEscapedString("currency", "KRW ")).code shouldBe ApiErrorCode.INVALID_CURRENCY
            rejected(CreatePaymentRequests.withEscapedString("paymentMethod", "card")) shouldBe ApiError(ApiErrorCode.INVALID_REQUEST, field = "paymentMethod")
            rejected(CreatePaymentRequests.withEscapedString("merchantSiteId", "s".repeat(65))).field shouldBe "merchantSiteId"
            rejected(CreatePaymentRequests.withEscapedString("merchantAccountId", "")).field shouldBe "merchantAccountId"
        }
        listOf("merchantAccountId", "merchantSiteId").forEach { field ->
            Then("$field with a control character, a line/paragraph separator or a bidi control is rejected (no log line injection, review round2 m-2)") {
                // JSON escape \uC6D0\uBB38(\uB530\uC634\uD45C \uC548): \n\u00B7\r\u00B7\t\u00B7\u0000\u00B7\u0085(NEL)\u00B7\u2028\u00B7\u2029\u00B7\u202E(RLO)\u00B7\u2066(LRI).
                listOf("a\\nFORGED", "a\\r", "a\\t", "a\\u0000", "a\\u0085", "a\\u2028", "a\\u2029", "a\\u202E", "a\\u2066").forEach { escaped ->
                    rejected(CreatePaymentRequests.withEscapedString(field, escaped)) shouldBe ApiError(ApiErrorCode.INVALID_REQUEST, field = field)
                }
            }
        }
        Then("the first violation in field order is reported") {
            rejected(CreatePaymentRequests.withEscapedString("merchantSiteId", "").replace("\"KRW\"", "\"krw\"")).field shouldBe "merchantSiteId"
        }
        Then("a '+' or leading-zero amount (JSON syntax error) keeps the field order: earlier field violations first, later ones after amount (review round1 n-5)") {
            // amount보다 앞 필드 위반이 먼저다.
            rejected(CreatePaymentRequests.withRawAmount("+1").replace("\"${CreatePaymentRequests.SITE}\"", "\"\"")) shouldBe
                ApiError(ApiErrorCode.INVALID_REQUEST, field = "merchantSiteId")
            // amount가 currency보다 앞이다.
            rejected(CreatePaymentRequests.withRawAmount("010").replace("\"KRW\"", "\"krw\"")) shouldBe ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount")
            // currency 자리의 숫자는 amount가 아니라 currency 위반이다(정상 amount를 INVALID_AMOUNT로 오분류하지 않는다).
            rejected(CreatePaymentRequests.valid().replace("\"KRW\"", "+1")) shouldBe ApiError(ApiErrorCode.INVALID_CURRENCY, field = "currency")
            rejected(CreatePaymentRequests.valid(orderNameJson = "007")) shouldBe ApiError(ApiErrorCode.INVALID_REQUEST, field = "orderName")
            // amount의 '+'는 뒤 필드의 (문법상 정상) 숫자 위반보다 먼저다.
            rejected(CreatePaymentRequests.withRawAmount("+1").replace("\"KRW\"", "5")) shouldBe ApiError(ApiErrorCode.INVALID_AMOUNT, field = "amount")
        }
    }

    Given("the Idempotency-Key header") {
        Then("exactly one value in the D-CPC-002 format is required") {
            CreatePaymentRequestParser.idempotencyKey(listOf("key-1:a.b_c")).value shouldBe "key-1:a.b_c"
            listOf(emptyList(), listOf("a", "b"), listOf(""), listOf("k".repeat(65)), listOf("key 1"), listOf("ключ")).forEach { values ->
                shouldThrow<RequestRejected> { CreatePaymentRequestParser.idempotencyKey(values) }.error shouldBe
                    ApiError(ApiErrorCode.INVALID_IDEMPOTENCY_KEY, field = "Idempotency-Key")
            }
        }
    }

    Given("the orderName rule (CP-API-001 §4.4: code points 1..100 = VARCHAR(100) utf8mb4)") {
        Then("the length unit is the Unicode code point") {
            OrderNameRule.isValid("😀".repeat(100)) shouldBe true // 200 UTF-16 units, 400 UTF-8 bytes
            OrderNameRule.isValid("😀".repeat(101)) shouldBe false
            OrderNameRule.isValid("가".repeat(100)) shouldBe true // 300 UTF-8 bytes
            OrderNameRule.isValid("a".repeat(101)) shouldBe false
            OrderNameRule.isValid("") shouldBe false
        }
        Then("control characters, line/paragraph separators and bidi controls are rejected; ZWJ and spaces are allowed") {
            listOf("a\tb", "a\nb", "a\u0000", "a\u007F", "a\u0085", "a ", "a ", "a‮", "a⁦").forEach { OrderNameRule.isValid(it) shouldBe false }
            OrderNameRule.isValid("👩‍💻") shouldBe true
            OrderNameRule.isValid("   ") shouldBe true
        }
    }
})

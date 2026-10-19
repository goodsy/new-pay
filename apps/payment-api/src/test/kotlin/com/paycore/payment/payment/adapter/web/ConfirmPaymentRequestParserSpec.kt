package com.paycore.payment.payment.adapter.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/** confirm 본문 엄격 파서(AUTH-001 §4.1 — 닫힌 필드 4개, CP-API 관례·AN-03과 같은 규칙): 형식 위반은 400(첫 위반 하나, 값 echo 없음). */
class ConfirmPaymentRequestParserSpec : BehaviorSpec({

    val ref = "aref_" + "A".repeat(22)
    fun body(vararg fields: Pair<String, String>) = ("{" + fields.joinToString(",") { "\"${it.first}\":${it.second}" } + "}").toByteArray()
    val valid = arrayOf("authenticationRef" to "\"$ref\"", "merchantOrderId" to "\"order-1\"", "amount" to "50000", "currency" to "\"KRW\"")

    Given("a valid confirm body") {
        Then("it parses into the four restated values") {
            val parsed = ConfirmPaymentRequestParser.parse(body(*valid))
            parsed.authenticationRef shouldBe ref
            parsed.merchantOrderId shouldBe "order-1"
            parsed.money.amountMinor shouldBe 50_000
            parsed.money.currency.value shouldBe "KRW"
        }
    }

    Given("bodies that break the schema") {
        Then("unknown or missing fields, null, a bad authenticationRef or order ID are INVALID_REQUEST; bad amount INVALID_AMOUNT; bad currency INVALID_CURRENCY") {
            mapOf(
                body(*valid, "extra" to "1") to ApiErrorCode.INVALID_REQUEST,
                body(*valid.filter { it.first != "merchantOrderId" }.toTypedArray()) to ApiErrorCode.INVALID_REQUEST,
                body(*valid.map { if (it.first == "authenticationRef") it.first to "null" else it }.toTypedArray()) to ApiErrorCode.INVALID_REQUEST,
                body(*valid.map { if (it.first == "authenticationRef") it.first to "\"aref_short\"" else it }.toTypedArray()) to ApiErrorCode.INVALID_REQUEST,
                body(*valid.map { if (it.first == "merchantOrderId") it.first to "\"bad order\"" else it }.toTypedArray()) to ApiErrorCode.INVALID_REQUEST,
                body(*valid.map { if (it.first == "amount") it.first to "\"50000\"" else it }.toTypedArray()) to ApiErrorCode.INVALID_AMOUNT,
                body(*valid.map { if (it.first == "amount") it.first to "0" else it }.toTypedArray()) to ApiErrorCode.INVALID_AMOUNT,
                body(*valid.map { if (it.first == "amount") it.first to "1.5" else it }.toTypedArray()) to ApiErrorCode.INVALID_AMOUNT,
                body(*valid.map { if (it.first == "currency") it.first to "\"krw\"" else it }.toTypedArray()) to ApiErrorCode.INVALID_CURRENCY,
                "not json".toByteArray() to ApiErrorCode.INVALID_REQUEST,
                ByteArray(0) to ApiErrorCode.INVALID_REQUEST,
                ("{\"authenticationRef\":\"$ref\",\"authenticationRef\":\"$ref\",\"merchantOrderId\":\"o\",\"amount\":1,\"currency\":\"KRW\"}").toByteArray() to ApiErrorCode.INVALID_REQUEST,
            ).entries.forEachIndexed { i, (b, code) ->
                withClue("case $i") { shouldThrow<RequestRejected> { ConfirmPaymentRequestParser.parse(b) }.error.code shouldBe code }
            }
        }
        Then("a payment ID path segment outside the server format is not a payment (404 — existence not revealed)") {
            ConfirmPaymentRequestParser.isPaymentIdLike("pay_01JA0000000000000000000001") shouldBe true
            listOf("", "x".repeat(65), "pay id", "pay_\u0000").forEach { ConfirmPaymentRequestParser.isPaymentIdLike(it) shouldBe false }
        }
    }
})

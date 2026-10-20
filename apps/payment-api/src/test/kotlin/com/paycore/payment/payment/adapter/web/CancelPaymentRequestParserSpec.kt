package com.paycore.payment.payment.adapter.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 취소 본문 엄격 파서(CANCEL-001 §4.1 CN-01 — 닫힌 필드 3개, CP-API 관례·confirm 파서와 같은 규칙): 형식 위반은 400(첫 위반 하나, 값 echo 없음). `merchantCancelId`는 D-CPC-002 형식
 * (1..64 `[A-Za-z0-9-_.:]` — 넘는 입력은 업무 거절이 아니라 400, rev 0.3 m-3), amount는 minor 정수 ≥ 1, currency는 대문자 3자. 가맹점 취소 사유 문구는 받지 않는다.
 */
class CancelPaymentRequestParserSpec : BehaviorSpec({

    fun body(vararg fields: Pair<String, String>) = ("{" + fields.joinToString(",") { "\"${it.first}\":${it.second}" } + "}").toByteArray()
    val valid = arrayOf("merchantCancelId" to "\"mc-0001\"", "amount" to "3000", "currency" to "\"KRW\"")
    fun with(field: String, value: String) = body(*valid.map { if (it.first == field) it.first to value else it }.toTypedArray())

    Given("a valid cancel body") {
        Then("it parses into merchantCancelId and the cancel Money") {
            val parsed = CancelPaymentRequestParser.parse(body(*valid))
            parsed.merchantCancelId.value shouldBe "mc-0001"
            parsed.money.amountMinor shouldBe 3_000
            parsed.money.currency.value shouldBe "KRW"
            CancelPaymentRequestParser.parse(with("merchantCancelId", "\"" + "a".repeat(64) + "\"")).merchantCancelId.value.length shouldBe 64
        }
    }

    Given("bodies that break the schema") {
        Then("unknown or missing fields (a merchant reason is not accepted), null, a bad merchantCancelId are INVALID_REQUEST; bad amount INVALID_AMOUNT; bad currency INVALID_CURRENCY") {
            mapOf(
                body(*valid, "reason" to "\"customer request\"") to ApiErrorCode.INVALID_REQUEST,
                body(*valid.filter { it.first != "merchantCancelId" }.toTypedArray()) to ApiErrorCode.INVALID_REQUEST,
                with("merchantCancelId", "null") to ApiErrorCode.INVALID_REQUEST,
                with("merchantCancelId", "\"\"") to ApiErrorCode.INVALID_REQUEST,
                with("merchantCancelId", "\"" + "a".repeat(65) + "\"") to ApiErrorCode.INVALID_REQUEST,
                with("merchantCancelId", "\"bad id\"") to ApiErrorCode.INVALID_REQUEST,
                with("merchantCancelId", "1234") to ApiErrorCode.INVALID_REQUEST,
                with("amount", "\"3000\"") to ApiErrorCode.INVALID_AMOUNT,
                with("amount", "0") to ApiErrorCode.INVALID_AMOUNT,
                with("amount", "-1") to ApiErrorCode.INVALID_AMOUNT,
                with("amount", "1.5") to ApiErrorCode.INVALID_AMOUNT,
                with("amount", "99999999999999999999") to ApiErrorCode.INVALID_AMOUNT,
                with("currency", "\"krw\"") to ApiErrorCode.INVALID_CURRENCY,
                body(*valid.filter { it.first != "currency" }.toTypedArray()) to ApiErrorCode.INVALID_CURRENCY,
                "not json".toByteArray() to ApiErrorCode.INVALID_REQUEST,
                "[]".toByteArray() to ApiErrorCode.INVALID_REQUEST,
                ByteArray(0) to ApiErrorCode.INVALID_REQUEST,
                ("﻿" + String(body(*valid))).toByteArray() to ApiErrorCode.INVALID_REQUEST,
                byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d) to ApiErrorCode.INVALID_REQUEST,
                ByteArray(CancelPaymentRequestParser.MAX_BODY_BYTES + 1) { 0x20 } to ApiErrorCode.INVALID_REQUEST,
                ("{\"merchantCancelId\":\"a\",\"merchantCancelId\":\"b\",\"amount\":1,\"currency\":\"KRW\"}").toByteArray() to ApiErrorCode.INVALID_REQUEST,
            ).entries.forEachIndexed { i, (b, code) ->
                withClue("case $i") { shouldThrow<RequestRejected> { CancelPaymentRequestParser.parse(b) }.error.code shouldBe code }
            }
        }
    }
})

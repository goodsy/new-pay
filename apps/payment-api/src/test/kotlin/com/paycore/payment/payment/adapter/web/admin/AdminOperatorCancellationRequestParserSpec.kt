package com.paycore.payment.payment.adapter.web.admin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 엄격 본문 해석·근거·사유 형식(ADMIN-PAYMENT-OPS-001 §4.3 L-1·§4.18 — 단위). 기대값은 명세 문장에서 직접: 근거 1~200자·문자·숫자·공백·`- _ . : / #`·첫끝 공백 거부·정규화 없음, 사유 1~500자·줄바꿈
 * 허용·그 밖 제어 문자 거부·앞뒤 공백 거부.
 */
class AdminOperatorCancellationRequestParserSpec : BehaviorSpec({

    val p = AdminOperatorCancellationRequestParser

    Given("purpose references") {
        Then("letters (incl. Hangul and supplementary), digits, space and - _ . : / # are accepted up to 200 UTF-16 units") {
            listOf("RECON-2026-0001", "대사 2026/10:#1_a.b", "𠀀", "x".repeat(200)).forEach { p.validReference(it) shouldBe true }
        }
        Then("empty, too long, leading or trailing space, control, other symbols and lone surrogates are refused (no trimming)") {
            listOf("", "x".repeat(201), " A", "A ", "A\nB", "A\tB", "A+B", "A@B", "\uD840", "Ａ！").forEach { p.validReference(it) shouldBe false }
        }
    }

    Given("reasons") {
        Then("line breaks are allowed inside, other control characters and surrounding whitespace are refused") {
            p.validReason("첫 줄\n둘째 줄") shouldBe true
            p.validReason("x".repeat(500)) shouldBe true
            listOf("", "x".repeat(501), " a", "a ", "a\u0000b", "a\u001Bb", "ab", "\na").forEach { p.validReason(it) shouldBe false }
        }
    }

    Given("I-102 (S9): Unicode format characters (Cf)") {
        Then("RLO, zero-width, BOM, isolates and tag characters are refused in reasons and references (the same set as ADMIN — ADMIN ⊆ PAYMENT)") {
            listOf("a\u202Eb", "a\u200Bb", "a\u200Db", "a\uFEFFb", "a\u2066b", "a" + String(Character.toChars(0xE0041)) + "b").forEach {
                p.validReason(it) shouldBe false
                p.validReference(it) shouldBe false
            }
            listOf("중복 승인 보정", "line one\nline two", "café").forEach { p.validReason(it) shouldBe true }
        }
    }

    Given("a correct body") {
        Then("parses to a command whose fields are the body values") {
            val body = AdminOpsTestClient.body(null, paymentId = AdminOpsTestClient.newPaymentId(), amountMinor = 7000, currency = "KRW")
            val ok = p.parse(body.toByteArray(), "req").shouldBeInstanceOf<AdminOperatorCancellationRequestParser.Parsed.Ok>()
            ok.command.money.amountMinor shouldBe 7000
            ok.command.approvalId.value shouldBe AdminOpsTestClient.approvalIdOf(body)
        }
        Then("invalid UTF-8 bytes are REQUEST_MALFORMED with every id as the placeholder") {
            val m = p.parse(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D), "req").shouldBeInstanceOf<AdminOperatorCancellationRequestParser.Parsed.Malformed>()
            m.audit.code.name shouldBe "REQUEST_MALFORMED"
            listOf(m.audit.approvalId, m.audit.paymentId, m.audit.requestedBy, m.audit.approvedBy).toSet() shouldBe setOf("MALFORMED")
        }
    }
})

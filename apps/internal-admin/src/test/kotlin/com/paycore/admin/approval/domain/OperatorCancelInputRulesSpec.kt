package com.paycore.admin.approval.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * 운영자 보정 취소 입력 형식(ADMIN-PAYMENT-OPS-001 §4.18 — `PurposeReferenceFormatSpec`·`ReasonCardDataSpec`, I-17·I-18): 서버는 자르거나 정규화하지 않는다.
 */
class OperatorCancelInputRulesSpec : FunSpec({

    test("PurposeReferenceFormat: letters, digits, space and - _ . : / # only; 1..200 UTF-16 units; no leading/trailing space; no normalization") {
        listOf("RECON-2026-0001", "대사-2026 #7", "TERM_1.2:3/4", "가".repeat(200), "𠀀", "ＡＢＣ", "٣٤").map(OperatorCancelInputRules::referenceValid) shouldBe List(7) { true }
        // I-92(S5 PAYMENT 대조): 숫자는 십진 숫자(Nd)만 — PAYMENT admin 해석기(`Character.isDigit`)와 같게. 로마 숫자(Nl)·위 첨자(No)는 거부(ADMIN이 더 넓으면 PAYMENT 400).
        listOf("Ⅻ", "RECON²", "①").map(OperatorCancelInputRules::referenceValid) shouldBe List(3) { false }
        listOf(
            "", " RECON", "RECON ", "a".repeat(201), "RECON\n1", "RECON\t1", "RECON​", "RECON", "RECON\uD800", "a,b", "a;b", "a'b", "a\"b", "a<b>",
            "͸", "RECON ",
        ).map(OperatorCancelInputRules::referenceValid) shouldBe List(16) { false }
        // 정규화 없음: 결합 문자 표기(e + U+0301)는 결합 부호(Mn)가 허용 집합 밖이라 거부 — 미리 결합한 é는 허용.
        listOf(OperatorCancelInputRules.referenceValid("café"), OperatorCancelInputRules.referenceValid("café")) shouldBe listOf(false, true)
    }

    test("Reason: 1..500, newline allowed, other control characters refused, no leading/trailing whitespace") {
        listOf("duplicate charge correction", "중복 승인 보정\n두 번째 줄 𠀀", "x".repeat(500)).map(OperatorCancelInputRules::reasonValid) shouldBe List(3) { true }
        listOf("", " a", "a ", "\na", "a\n", "a\tb", "a\rb", "a\u0000b", "x".repeat(501), "a\uDC00", "　a").map(OperatorCancelInputRules::reasonValid) shouldBe
            List(11) { false }
        // I-92: PAYMENT 해석기처럼 사설 영역·미할당 문자도 거부(ADMIN이 받아도 PAYMENT가 400으로 거절한다).
        listOf("a" + String(Character.toChars(0xE000)) + "b", "a" + String(Character.toChars(0x0378)) + "b", "a" + String(Character.toChars(0xF0000)) + "b")
            .map(OperatorCancelInputRules::reasonValid) shouldBe List(3) { false }
    }

    test("I-102 (S9): Unicode format characters (Cf — RLO, zero-width, BOM, isolates, tags) are refused in reasons as in references (ADMIN ⊆ PAYMENT kept)") {
        listOf("a\u202Eb", "a\u200Bb", "a\u200Db", "a\uFEFFb", "a\u2066b", "a" + String(Character.toChars(0xE0041)) + "b").map(OperatorCancelInputRules::reasonProblem) shouldBe List(6) { "CHARACTER_NOT_ALLOWED" }
        listOf("a\u202Eb", "a\u200Bb", "a\u200Db", "a\uFEFFb", "a\u2066b", "a" + String(Character.toChars(0xE0041)) + "b").map(OperatorCancelInputRules::referenceValid) shouldBe List(6) { false }
        // 보이는 문자·줄바꿈은 그대로 허용(형식 문자만 거부).
        listOf("중복 승인 보정", "line one\nline two", "café").map(OperatorCancelInputRules::reasonValid) shouldBe List(3) { true }
    }

    test("ReasonCardData (I-17): 13..19 digit runs, with single spaces or hyphens between digits, are refused; shorter numbers and amounts are allowed") {
        listOf(
            "card 4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111", "1234567890123", "1234567890123456789", "ref 5500-0000-0000-0004 x",
        ).map(OperatorCancelInputRules::reasonValid) shouldBe List(6) { false }
        listOf("amount 7000 won", "ticket 123456789012", "tel 010-1234-5678", "12345678901234567890 is too long to be a card").map(OperatorCancelInputRules::reasonValid) shouldBe
            listOf(true, true, true, true)
    }

    test("S5 review L-3: card digit runs in any decimal digit script (\\p{Nd} — full-width, Arabic-Indic) are refused, not only ASCII") {
        val fullWidth = "４１１１".repeat(4)
        val arabicIndic = "٤١١١".repeat(4)
        listOf("card $fullWidth", "card $arabicIndic", "card 4111 １１１１ 1111 1111").map(OperatorCancelInputRules::reasonValid) shouldBe List(3) { false }
        OperatorCancelInputRules.containsCardNumber(fullWidth) shouldBe true
    }

    test("I-111 (S8): every refusal has one fixed reason code (the value itself is never echoed); valid input has none") {
        mapOf(
            "" to "LENGTH", "a".repeat(201) to "LENGTH", "RECON\uD800" to "MALFORMED_TEXT", " RECON" to "EDGE_WHITESPACE", "a;b" to "CHARACTER_NOT_ALLOWED",
            "RECON\n1" to "CHARACTER_NOT_ALLOWED", "RECON-20261028-000001" to "CARD_NUMBER_PATTERN", "RECON-2026-0001" to null,
        ).let { cases -> cases.mapValues { OperatorCancelInputRules.referenceProblem(it.key) } shouldBe cases }
        mapOf(
            "" to "LENGTH", "x".repeat(501) to "LENGTH", "a\uDC00" to "MALFORMED_TEXT", " a" to "EDGE_WHITESPACE", "a\n" to "EDGE_WHITESPACE",
            "a\tb" to "CHARACTER_NOT_ALLOWED", "card 4111111111111111" to "CARD_NUMBER_PATTERN", "duplicate charge correction" to null,
        ).let { cases -> cases.mapValues { OperatorCancelInputRules.reasonProblem(it.key) } shouldBe cases }
        // 기존 판정과 같다(valid ⇔ 문제 없음).
        listOf("RECON-2026-0001", " RECON", "RECON-20261028-000001").map { OperatorCancelInputRules.referenceValid(it) == (OperatorCancelInputRules.referenceProblem(it) == null) } shouldBe
            List(3) { true }
    }

    test("S5 review L-1: a card-like digit run in the purpose reference is refused too (ADMIN ⊆ PAYMENT kept — ADMIN is only stricter)") {
        listOf("RECON-4111-1111-1111-1111", "4111111111111111", "REF ４１１１１１１１１１１１１１１１")
            .map(OperatorCancelInputRules::referenceValid) shouldBe List(3) { false }
        listOf("RECON-2026-0001", "TERM 123456789012").map(OperatorCancelInputRules::referenceValid) shouldBe listOf(true, true)
    }
})

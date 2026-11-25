package com.paycore.admin.merchantops

/**
 * 카드번호 패턴 공통 시험 벡터(SEC-BIZ-001 M-1 — BR-BIZ-305, D-FLOW-007 ①, D-GOV-001 ①). 판정 정본 = `OperatorCancelInputRules.containsCardNumber`
 * (`OperatorCancelInputRulesSpec` ReasonCardData·S5 L-3·I-111 사례). MASTER `CardNumberVectors`·화면 `lib/businessProfile.test.ts`와 **같은 목록**(복제 — 바꾸면 세 곳 함께).
 */
object BusinessCardNumberVectors {
    val CARD_LIKE: List<String> = listOf(
        "card 4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111", "1234567890123", "1234567890123456789", "ref 5500-0000-0000-0004 x",
        "card " + "４１１１".repeat(4), "card " + "٤١١١".repeat(4), "card 4111 １１１１ 1111 1111", "RECON-20261028-000001",
    )

    val NOT_CARD: List<String> = listOf(
        "amount 7000 won", "ticket 123456789012", "tel 010-1234-5678", "12345678901234567890 is too long to be a card", "RECON-2026-0001", "INQ-2026-1105-0001",
    )
}

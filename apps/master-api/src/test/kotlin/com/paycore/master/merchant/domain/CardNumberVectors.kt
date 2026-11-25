package com.paycore.master.merchant.domain

/**
 * 카드번호 패턴 공통 시험 벡터(SEC-BIZ-001 M-1 — BR-BIZ-305, D-FLOW-007 ①, D-GOV-001 ① "판정 복제본 공통 벡터"). 판정 정본은 ADMIN `OperatorCancelInputRules.containsCardNumber`이고,
 * 이 목록은 그 시험(`OperatorCancelInputRulesSpec` ReasonCardData·S5 L-3·I-111)과 FINANCIAL `ReacquisitionCommandParserSpec`이 이미 쓰는 같은 사례다. ADMIN
 * `BusinessCardNumberVectors`·화면 `lib/businessProfile.test.ts`가 같은 목록을 그대로 복제한다(모듈 경계를 넘는 시험 코드 공유 없음 — 바꾸면 세 곳을 함께 바꾼다).
 */
object CardNumberVectors {
    /** 카드번호로 보는 사례(13~19자리 십진 숫자 — 숫자 사이 공백·하이픈 하나 허용, 모든 십진 숫자 문자, Luhn 없음). */
    val CARD_LIKE: List<String> = listOf(
        "card 4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111", "1234567890123", "1234567890123456789", "ref 5500-0000-0000-0004 x",
        "card " + "４１１１".repeat(4), "card " + "٤١١١".repeat(4), "card 4111 １１１１ 1111 1111", "RECON-20261028-000001",
    )

    /** 카드번호가 아닌 숫자열 사유(통과해야 한다): 짧은 숫자·금액·전화·20자리 이상·구분 기호로 끊긴 참조. */
    val NOT_CARD: List<String> = listOf(
        "amount 7000 won", "ticket 123456789012", "tel 010-1234-5678", "12345678901234567890 is too long to be a card", "RECON-2026-0001", "INQ-2026-1105-0001",
    )
}

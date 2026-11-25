package com.paycore.master.merchant.domain

/**
 * 카드번호처럼 보이는 숫자 연속(SEC-BIZ-001 M-1 — BR-BIZ-305, D-FLOW-007 ①). 판정 정본 ADMIN `OperatorCancelInputRules.containsCardNumber`와 **같은 판정**을 모듈 경계를 넘지 않게 복제한다
 * (FINANCIAL `CardNumberPattern`과도 같은 식): 십진 숫자(`\p{Nd}` — ASCII·전각·아랍-인도 숫자 모두) 13~19자리, 숫자 사이에 공백 또는 하이픈 하나까지 허용, 앞뒤가 숫자가 아니어야 한다,
 * Luhn 검사 없음(오탐을 받아들이고 거부 쪽으로 둔다). 같은 판정인지는 세 곳(MASTER·ADMIN·화면) 시험이 같은 공통 벡터로 고정한다(`CardNumberVectors`).
 */
internal object CardNumberPattern {
    private val CARD_NUMBER = Regex("(?<!\\p{Nd})(?:\\p{Nd}[ -]?){12,18}\\p{Nd}(?!\\p{Nd})")

    fun matches(text: String): Boolean = CARD_NUMBER.containsMatchIn(text)
}

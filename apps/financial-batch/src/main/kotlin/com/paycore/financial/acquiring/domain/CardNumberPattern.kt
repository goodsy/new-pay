package com.paycore.financial.acquiring.domain

/**
 * 카드 번호처럼 보이는 숫자 연속(ACQ-ADMIN-001 rev 0.7 — B2, I-23 권장안). ADMIN `OperatorCancelInputRules.containsCardNumber`와 **같은 판정**이다: 십진 숫자(`\p{Nd}` —
 * ASCII·전각·아랍-인도 숫자 모두) 13~19자리, 숫자 사이에 공백 또는 하이픈 하나까지 허용, 앞뒤가 숫자가 아니어야 한다, Luhn 검사 없음(오탐을 받아들이고 거부 쪽으로 둔다).
 * 쓰는 곳: 재매입 명령 근거 참조(evidenceRef) — FINANCIAL append-only 기록(결정·승인 표)에 카드 번호가 남지 않게 채널을 직접 부르는 다른 호출자도 막는다(ADMIN 거부와 이중).
 * 두 모듈이 같은 판정인지는 양쪽 시험이 같은 벡터로 고정한다(ReacquisitionCommandParserSpec ↔ ADMIN OperatorCancelInputRulesSpec).
 */
object CardNumberPattern {
    private val CARD_NUMBER = Regex("(?<!\\p{Nd})(?:\\p{Nd}[ -]?){12,18}\\p{Nd}(?!\\p{Nd})")

    fun matches(text: String): Boolean = CARD_NUMBER.containsMatchIn(text)
}

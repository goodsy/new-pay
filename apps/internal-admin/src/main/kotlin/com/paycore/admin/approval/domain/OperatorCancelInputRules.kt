package com.paycore.admin.approval.domain

/**
 * 운영자 보정 취소 요청 입력 형식(ADMIN-PAYMENT-OPS-001 §4.18, D-OPS-004, I-17·I-18). 서버는 **자르거나 정규화하지 않는다**(받은 코드 포인트 그대로 저장·지문 — 화면이 입력 시
 * 정리). 길이는 UTF-16 단위.
 * - 근거: 1~200, 올바른 UTF-16, 허용 = Unicode 문자(L*)·십진 숫자(Nd — I-92 PAYMENT와 같게)·U+0020·`- _ . : / #`, 첫·끝 공백 거부, 줄바꿈·제어·형식·사설·미할당 문자는 허용 집합 밖이라 거부.
 * - 사유: 1~500, 올바른 UTF-16, 줄바꿈(LF)만 허용하고 그 밖 제어 문자 거부, 앞뒤 공백(공백·줄바꿈 포함) 거부, 카드 번호 패턴(13~19자리 숫자 — 숫자 사이 공백·하이픈 1개
 *   허용) 거부(I-17 — Luhn 없이 보수적으로).
 */
object OperatorCancelInputRules {
    const val MAX_REFERENCE = 200
    const val MAX_REASON = 500
    private const val REFERENCE_PUNCTUATION = "-_.:/#"
    /** 모든 십진 숫자 문자(`\p{Nd}` — 전각·아랍 인도 숫자 포함, S5 리뷰 L-3)로 13~19자리, 숫자 사이 공백·하이픈 1개 허용. */
    private val CARD_NUMBER = Regex("(?<!\\p{Nd})(?:\\p{Nd}[ -]?){12,18}\\p{Nd}(?!\\p{Nd})")

    fun referenceValid(text: String): Boolean = referenceProblem(text) == null

    fun reasonValid(text: String): Boolean = reasonProblem(text) == null

    /**
     * 근거 거절 이유(I-111 — 화면 표시용 고정 code, 값 원문은 응답에 넣지 않는다). 판정 순서: 길이 → UTF-16 형식 → 앞뒤 공백 → 허용 문자 → 카드 번호 패턴. 없으면 null.
     */
    fun referenceProblem(text: String): String? = when {
        text.length !in 1..MAX_REFERENCE -> LENGTH
        !wellFormed(text) -> MALFORMED_TEXT
        text.first() == ' ' || text.last() == ' ' -> EDGE_WHITESPACE
        !text.codePoints().allMatch { cp -> cp == ' '.code || REFERENCE_PUNCTUATION.indexOf(cp.toChar()) >= 0 && cp < 0x80 || isLetterOrNumber(cp) } -> CHARACTER_NOT_ALLOWED
        // S5 리뷰 L-1: 근거에도 카드 번호 패턴을 거부한다(ADMIN이 더 엄격 — ADMIN ⊆ PAYMENT 유지).
        containsCardNumber(text) -> CARD_NUMBER_PATTERN
        else -> null
    }

    /** 사유 거절 이유(I-111): 길이 → UTF-16 형식 → 앞뒤 공백(줄바꿈 포함) → 제어·사설·미할당 문자 → 카드 번호 패턴. 없으면 null. */
    fun reasonProblem(text: String): String? = when {
        text.length !in 1..MAX_REASON -> LENGTH
        !wellFormed(text) -> MALFORMED_TEXT
        Character.isWhitespace(text.first()) || Character.isWhitespace(text.last()) || Character.isSpaceChar(text.first()) || Character.isSpaceChar(text.last()) ->
            EDGE_WHITESPACE
        // I-92: PAYMENT admin 해석기와 같게 사설 영역·미할당 문자도 거부(ADMIN이 더 넓으면 PAYMENT가 400 → OWNER_REJECTED). CR은 ADMIN이 더 엄격(LF만).
        text.codePoints().anyMatch { cp ->
            val type = Character.getType(cp)
            // I-102(S9): 형식 문자(Cf — RLO U+202E·zero-width U+200B 등 표시를 속이는 문자)도 거부 — PAYMENT 해석기와 함께 바꿔 ADMIN ⊆ PAYMENT 유지.
            (cp != '\n'.code && type == Character.CONTROL.toInt()) || type == Character.FORMAT.toInt() || type == Character.PRIVATE_USE.toInt() ||
                type == Character.UNASSIGNED.toInt()
        } -> CHARACTER_NOT_ALLOWED
        containsCardNumber(text) -> CARD_NUMBER_PATTERN
        else -> null
    }

    fun containsCardNumber(text: String): Boolean = CARD_NUMBER.containsMatchIn(text)

    const val LENGTH = "LENGTH"
    const val MALFORMED_TEXT = "MALFORMED_TEXT"
    const val EDGE_WHITESPACE = "EDGE_WHITESPACE"
    const val CHARACTER_NOT_ALLOWED = "CHARACTER_NOT_ALLOWED"
    const val CARD_NUMBER_PATTERN = "CARD_NUMBER_PATTERN"

    /** 문자(L*)·십진 숫자(Nd) — PAYMENT admin 해석기(`Character.isLetter`·`isDigit`)와 같은 집합(I-92). */
    private fun isLetterOrNumber(cp: Int): Boolean = Character.isLetter(cp) || Character.isDigit(cp)

    private fun wellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return false
                    i += 2
                }
                Character.isLowSurrogate(c) -> return false
                else -> i++
            }
        }
        return true
    }
}

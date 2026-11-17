package com.paycore.master.merchant.domain

/**
 * 이메일(§4.2·C-20 — 형식만): 3~254자 ASCII, `local@domain`(`@` 하나), local 1~64자 `[A-Za-z0-9._%+-]`, domain은 점으로 나뉜 라벨 2개 이상
 * `[A-Za-z0-9-]`(라벨 1~63, 마지막 라벨 2자 이상). 대소문자 보존(정규화 없음). DB CHECK(`ck_breg_email`·`ck_bcon_email`)와 같은 집합 이하.
 * 개인정보 — [toString]은 마스킹.
 */
@JvmInline
value class EmailAddress(val value: String) {
    init {
        BusinessTextRule.require("email address", errorOf(value))
    }

    override fun toString(): String = "EmailAddress(${BusinessPiiMask.email(value)})"

    companion object {
        const val MAX_LENGTH = 254
        private const val MAX_LOCAL = 64
        private const val MAX_LABEL = 63

        fun errorOf(raw: String): BusinessFieldErrorCode? {
            if (raw.isEmpty()) return BusinessFieldErrorCode.REQUIRED
            if (raw.length > MAX_LENGTH) return BusinessFieldErrorCode.TOO_LONG
            val parts = raw.split('@')
            if (parts.size != 2) return BusinessFieldErrorCode.FORMAT
            val (local, domain) = parts
            if (local.length !in 1..MAX_LOCAL || !local.all { it.isAsciiLetterOrDigit() || it in "._%+-" }) return BusinessFieldErrorCode.FORMAT
            val labels = domain.split('.')
            if (labels.size < 2 || labels.any { it.length !in 1..MAX_LABEL || !it.all { c -> c.isAsciiLetterOrDigit() || c == '-' } }) return BusinessFieldErrorCode.FORMAT
            if (labels.last().length < 2) return BusinessFieldErrorCode.FORMAT
            return null
        }

        private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
    }
}
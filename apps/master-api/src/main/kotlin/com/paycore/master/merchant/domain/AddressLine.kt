package com.paycore.master.merchant.domain

/** 주소 한 줄(기본주소·상세주소 — §4.2): 1~200 code point, 같은 문자 규칙. 개인정보일 수 있다 — [toString]은 전부 가린다(이력 마스킹은 [BusinessPiiMask]). */
@JvmInline
value class AddressLine(val value: String) {
    init {
        BusinessTextRule.require("address line", errorOf(value))
    }

    override fun toString(): String = "AddressLine(***)"

    companion object {
        const val MAX_CODE_POINTS = 200

        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.errorOf(raw, MAX_CODE_POINTS)
    }
}
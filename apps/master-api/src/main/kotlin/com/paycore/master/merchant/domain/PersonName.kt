package com.paycore.master.merchant.domain

/** 대표자·등록 담당자 이름(§4.2): 1~50 code point, [DisplayName]과 같은 문자 규칙. 개인정보 — [toString]은 마스킹(§9, [BusinessPiiMask]). */
@JvmInline
value class PersonName(val value: String) {
    init {
        BusinessTextRule.require("person name", errorOf(value))
    }

    override fun toString(): String = "PersonName(${BusinessPiiMask.name(value)})"

    companion object {
        const val MAX_CODE_POINTS = 50

        /** 입력 해석기(BIZ-2 parser)용 판정 — null = 통과. */
        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.errorOf(raw, MAX_CODE_POINTS)
    }
}
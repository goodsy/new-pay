package com.paycore.master.merchant.domain

/** 업태·종목 이름(§4.2): 1~50 code point, [DisplayName]과 같은 문자 규칙(C-19 기술 상한). 개인정보 아님(원문 이력). */
@JvmInline
value class CategoryName(val value: String) {
    init {
        BusinessTextRule.require("category name", errorOf(value))
    }

    companion object {
        const val MAX_CODE_POINTS = 50

        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.errorOf(raw, MAX_CODE_POINTS)
    }
}
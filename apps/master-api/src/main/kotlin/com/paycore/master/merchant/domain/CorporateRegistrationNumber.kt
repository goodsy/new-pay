package com.paycore.master.merchant.domain

/**
 * 법인등록번호(§4.2 — 정책 §5 "13자리 형식"): ASCII 숫자 13자리, 체크섬 없음, 정규화 없음(하이픈은 화면 표시만 — X-14). UNIQUE 아님(TBD-01·C-30).
 * 노출: 상세는 원문(D-BIZ-006 ②), [toString]·[masked]·이력은 앞 6자리만.
 */
@JvmInline
value class CorporateRegistrationNumber(val value: String) {
    init {
        BusinessTextRule.require("corporate registration number", errorOf(value))
    }

    fun masked(): String = BusinessPiiMask.corporateNumber(value)

    override fun toString(): String = "CorporateRegistrationNumber(${masked()})"

    companion object {
        const val LENGTH = 13

        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.digitsErrorOf(raw, LENGTH)
    }
}
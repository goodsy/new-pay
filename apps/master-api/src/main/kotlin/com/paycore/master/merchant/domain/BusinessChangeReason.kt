package com.paycore.master.merchant.domain

/**
 * 중요정보 변경 사유(§4.5·C-13): 1~200 code point, 같은 문자 규칙(줄바꿈 불가). 원문은 MASTER 변경 이력에만 남는다(ADMIN 감사는 `reason=PRESENT`).
 * 자유 문구라 운영자가 개인정보를 적을 수 있다(L-4 — 화면 안내만).
 */
@JvmInline
value class BusinessChangeReason(val value: String) {
    init {
        BusinessTextRule.require("change reason", errorOf(value))
    }

    override fun toString(): String = "BusinessChangeReason(***)"

    companion object {
        const val MAX_CODE_POINTS = 200

        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.errorOf(raw, MAX_CODE_POINTS)
    }
}
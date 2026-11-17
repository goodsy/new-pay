package com.paycore.master.merchant.domain

/** 국내 우편번호(§4.2): ASCII 숫자 5자리. */
@JvmInline
value class PostalCode(val value: String) {
    init {
        BusinessTextRule.require("postal code", errorOf(value))
    }

    companion object {
        const val LENGTH = 5

        fun errorOf(raw: String): BusinessFieldErrorCode? = BusinessTextRule.digitsErrorOf(raw, LENGTH)
    }
}
package com.paycore.master.merchant.domain

/** 정책 코드(수수료·정산 정책의 이름, D-MS2-003). 1~32자 `[A-Z0-9_-]`. */
@JvmInline
value class PolicyCode(val value: String) {
    init {
        require(value.length in 1..MAX && value.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) { "policyCode must be 1..$MAX characters of [A-Z0-9_-]" }
    }

    private companion object {
        const val MAX = 32
    }
}

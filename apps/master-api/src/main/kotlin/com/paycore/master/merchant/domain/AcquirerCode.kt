package com.paycore.master.merchant.domain

/** 매입사(acquirer) 코드(D-MS2-004 — 카드사 개통의 키). 1~32자 `[A-Z0-9_]`. */
@JvmInline
value class AcquirerCode(val value: String) {
    init {
        require(value.length in 1..MAX && value.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) { "acquirerCode must be 1..$MAX characters of [A-Z0-9_]" }
    }

    private companion object {
        const val MAX = 32
    }
}

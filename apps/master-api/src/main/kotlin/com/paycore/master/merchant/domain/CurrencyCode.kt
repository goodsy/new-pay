package com.paycore.master.merchant.domain

/** ISO 4217 alphabetic 통화 코드(대문자 3자, PAYMENT CurrencyCode와 같은 형식). 허용 여부는 통화 활성화 기록이 정한다(D-MD-018). */
@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.length == 3 && value.all { it in 'A'..'Z' }) { "currency must be an ISO 4217 alphabetic code (3 upper-case letters)" }
    }
}

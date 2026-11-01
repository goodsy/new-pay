package com.paycore.master.merchant.domain

/** 매입사(acquirer) 코드(D-MS2-004 — 카드사 개통의 키). 기관 식별 코드 공통 형식 `^[A-Z0-9]{1,32}$`(D-INST-CODE-001 — 하이픈·밑줄 금지). */
@JvmInline
value class AcquirerCode(val value: String) {
    init {
        require(InstitutionCodeFormat.matches(value)) { "acquirerCode must be 1..32 characters of [A-Z0-9]" }
    }
}

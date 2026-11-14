package com.paycore.master.merchant.domain

/**
 * 가맹점 코드 예약어(MERCHANT-CODE-001 §3.1 C-2 — 코드 상수, 정책 변경은 명세 갱신으로). 코드가 예약어와 **같거나 예약어로 시작하면** 예약이다(접두 일치 — `adminshop`·`test01`도 거절).
 * [DEMO_SEED_CODES]는 Demo seed 고정 코드(C-6 `demoshop01` — 접두 `demo`)의 유일한 예외다. 예외도 UNIQUE를 우회하지 않는다(두 번째 배정은 `MERCHANT_CODE_TAKEN`).
 */
object MerchantCodeReservedWords {
    val WORDS: List<String> = listOf("paycore", "admin", "system", "test", "live", "demo", "api", "root", "null", "undefined")

    val DEMO_SEED_CODES: Set<String> = setOf("demoshop01")

    fun isReserved(code: String): Boolean = code !in DEMO_SEED_CODES && WORDS.any { code.startsWith(it) }
}

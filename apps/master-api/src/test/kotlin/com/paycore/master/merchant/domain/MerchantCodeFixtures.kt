package com.paycore.master.merchant.domain

import java.security.SecureRandom

/**
 * 가맹점 코드 Named Test Fixture(MERCHANT-CODE-001 §8 — Test Fixture Policy: 업무 조건이 이름에 드러나게, src/test 전용). 값은 시험 전용이다.
 * - [VALID]·경계 [MIN_LENGTH](6)·[MAX_LENGTH](14)는 §3.1 형식을 만족하는 고정값.
 * - [unique]는 공유 DB 시험에서 서로 다른 시험이 같은 코드를 잡지 않게 하는 **형식 안의** 임의 코드(첫 글자 `t` — 예약어·채우기 접두 `m`과 겹치지 않음, 14자).
 */
object MerchantCodeFixtures {
    const val VALID = "shopabcd12"
    const val MIN_LENGTH = "abcdef"
    val MAX_LENGTH = "a" + "b".repeat(13)

    private val random = SecureRandom()
    private const val TAIL = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun unique(): MerchantCode = MerchantCode("t" + (1..13).map { TAIL[random.nextInt(TAIL.length)] }.joinToString(""))
}

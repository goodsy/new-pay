package com.paycore.master.merchant.domain

import java.security.SecureRandom

/**
 * 가맹점 코드 Named Test Fixture(MERCHANT-CODE-001 §8 — Test Fixture Policy: 업무 조건이 이름에 드러나게, src/test 전용). 값은 시험 전용이다.
 * - [VALID]·경계 [MIN_LENGTH](6)·[MAX_LENGTH](14)는 §3.1 형식을 만족하는 고정값.
 * - [unique]는 공유 DB 시험에서 서로 다른 시험이 같은 코드를 잡지 않게 하는 **형식 안의** 임의 코드(고정 접두 `tx` + 임의 12자 = 14자).
 *   접두 `tx`는 어느 예약어(`test` 포함 — 접두 일치)와도, 채우기 접두 `m`과도 겹치지 않는다. 이전 접두 `t` 한 글자는 임의 꼬리가 `est…`일 때
 *   예약어 `test`로 시작하는 코드를 만들어 `MERCHANT_CODE_RESERVED`로 시험이 가끔 실패했다(MCODE-gate3 ContractSpec — 시험 Fixture 결함, 업무 규칙 변경 없음).
 */
object MerchantCodeFixtures {
    const val VALID = "shopabcd12"
    const val MIN_LENGTH = "abcdef"
    val MAX_LENGTH = "a" + "b".repeat(13)

    private val random = SecureRandom()
    private const val TAIL = "abcdefghijklmnopqrstuvwxyz0123456789"

    const val UNIQUE_PREFIX = "tx"

    fun unique(): MerchantCode = MerchantCode(UNIQUE_PREFIX + (1..12).map { TAIL[random.nextInt(TAIL.length)] }.joinToString(""))

    /** MC-1b: 1회 변경을 마친 가맹점의 은퇴 코드(seq 1 — 영구 점유, D-MCODE-002 ④). 변경 이력이 없는 가맹점이면 IllegalState. */
    fun retiredOf(merchant: Merchant): MerchantCode {
        check(merchant.codeAssignments.size == MerchantCodeAssignment.CHANGE) { "a merchant with a change history" }
        return merchant.codeAssignments.first().code
    }
}

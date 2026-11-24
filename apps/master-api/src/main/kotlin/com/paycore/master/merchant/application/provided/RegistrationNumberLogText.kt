package com.paycore.master.merchant.application.provided

/**
 * BRN-UNMASK-001(D-BIZ-007): 조회 View는 사업자등록번호 원문을 담지만 `toString`(로그·예외 메시지로 흘러가는 문자열)에는 마스킹 값만 쓴다 — 로그 마스킹 유지.
 * 모양은 `BusinessRegistrationNumber.masked()`와 같다(앞 3자리 + `*` 7개). View 값은 저장 원문이라 길이는 늘 10이다.
 */
internal fun registrationNumberLogText(raw: String): String = raw.take(VISIBLE_DIGITS) + "*".repeat(MASKED_DIGITS)

private const val VISIBLE_DIGITS = 3
private const val MASKED_DIGITS = 7

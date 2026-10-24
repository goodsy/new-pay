package com.paycore.simulator.merchant.application.required

/**
 * 서명 한 쌍 대조 Port(WEBHOOK-001 §21.0, WH-19·WH-20): HMAC-SHA256(키 = 비밀 문자열 전체의 UTF-8 바이트, 대상 = t의 ASCII 숫자 + "." + 본문 바이트)를 [v1Hex]와
 * **상수 시간**으로 비교한다(구현 = adapter — JCE). 비밀·서명 값을 로그·예외에 넣지 않는다.
 */
fun interface WebhookSignatureMatcher {
    fun matches(secret: ByteArray, timestampSeconds: Long, body: ByteArray, v1Hex: String): Boolean
}

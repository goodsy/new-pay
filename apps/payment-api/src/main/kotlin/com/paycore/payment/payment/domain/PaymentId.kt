package com.paycore.payment.payment.domain

/**
 * 서버가 생성하는 Payment 식별자(생성은 application의 Port 책임). 영속 열(VARCHAR(64))과 같은 상한 64자와 올바른 UTF-16(짝 없는 서로게이트 없음)을 강제한다(CANCEL-001 mid-review #1 m-2 —
 * 감사 행 등에 쓰일 때 1406·1366 저장 결함이 아니라 생성 시점 거부). 예외 메시지에 값을 넣지 않는다.
 */
@JvmInline
value class PaymentId(val value: String) {
    init {
        require(value.isNotEmpty()) { "paymentId must not be empty" }
        require(value.length <= MAX_LENGTH) { "paymentId must be at most $MAX_LENGTH characters" }
        Utf8Text.requireWellFormed(value, "paymentId")
    }

    companion object {
        const val MAX_LENGTH = 64
    }
}

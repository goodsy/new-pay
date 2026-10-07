package com.paycore.payment.payment.domain

/**
 * 결제창·후속 고객 단계에서 Payment를 지목하는 외부 키(CP-CONTRACT-001 §5). **자격 증명이 아니다**(후속 API도 MID 인증을 요구한다).
 * 생성 규칙(128bit 이상 난수 등)은 CP-API-001 생성기 책임이다. domain은 기술 불변식만 강제한다: 1..[MAX_LENGTH]자(영속 컬럼
 * VARCHAR(64), 길이 초과를 DB 오류로 미루지 않는다), 짝 없는 UTF-16 서로게이트 거부(JDBC `?` 치환으로 다른 키가 같은 값이 되는 것 방지).
 * 입력 그대로 비교한다(DB utf8mb4_0900_bin). 오류 메시지에 값을 넣지 않는다.
 */
@JvmInline
value class PaymentKey(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH) { "paymentKey must be 1..$MAX_LENGTH characters" }
        Utf8Text.requireWellFormed(value, "paymentKey")
    }

    companion object {
        const val MAX_LENGTH = 64
    }
}

/**
 * 결제수단. 초기 범위는 국내 신용카드(CARD)뿐이다(CP-CONTRACT-001 §3 "초기 확정"). 체크카드·할부·인증 범위 추가는 사용자 채택 전이며
 * 새 값을 추가하면 영속 CHECK(ck_payment_method)도 expand migration으로 함께 넓힌다.
 */
enum class PaymentMethod { CARD }

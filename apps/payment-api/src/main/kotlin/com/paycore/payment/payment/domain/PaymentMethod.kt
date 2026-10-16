package com.paycore.payment.payment.domain

/**
 * 결제수단. 초기 범위는 국내 신용카드(CARD)뿐이다(CP-CONTRACT-001 §3 "초기 확정"). 체크카드·할부·인증 범위 추가는 사용자 채택 전이며
 * 새 값을 추가하면 영속 CHECK(ck_payment_method)도 expand migration으로 함께 넓힌다.
 */
enum class PaymentMethod { CARD }

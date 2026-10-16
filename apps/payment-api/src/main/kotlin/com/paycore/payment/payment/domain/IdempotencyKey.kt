package com.paycore.payment.payment.domain

/**
 * 가맹점이 보낸 API 멱등키(`Idempotency-Key`). 범위는 (environment, 인증된 MID, operation, key)이며 같은 키·같은 내용은 저장된
 * 최초 결과, 다른 내용은 충돌이다(시도 §5, D-CP-003). 비교는 입력 그대로(D-CP-001), 형식은 D-CPC-002. 주문 키가 아니다.
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        MerchantRequestIdFormat.requireValid(value, "idempotencyKey")
    }
}

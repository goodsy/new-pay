package com.paycore.payment.payment.domain

/**
 * 가맹점 취소 요청 ID(D-CANCEL-002, 사용자 확정 2026-10-17): 가맹점 취소의 업무 효과 키. (Payment, merchantCancelId)는 유일하고, 같은 ID·같은
 * 내용은 Idempotency-Key가 달라도 같은 취소 operation이다. 형식은 D-CPC-002([MerchantRequestIdFormat]), 입력 그대로 비교(D-CP-001).
 */
@JvmInline
value class MerchantCancelId(val value: String) {
    init {
        MerchantRequestIdFormat.requireValid(value, "merchantCancelId")
    }
}

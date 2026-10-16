package com.paycore.payment.payment.domain

/**
 * 주문 키 = (environment, merchantAccountId, merchantOrderId) — 확정 문서 §1. 주문은 MID 범위이며 다른 MID·다른 환경은 별개의 주문이다.
 * 이 키당 OrderPaymentControl은 하나다(영속 유일성은 CP-DB의 UNIQUE 제약으로 보장한다).
 */
data class OrderKey(
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val merchantOrderId: MerchantOrderId,
)

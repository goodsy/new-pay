package com.paycore.payment.payment.application.provided

/**
 * 저장·재생되는 업무 거절(D-CPC-007). domain 판정 이름을 그대로 쓴다. HTTP 코드(ORDER_INTENT_CONFLICT 등) 매핑은 CP-API-001(계약 §9).
 */
enum class CreatePaymentRejection {
    FIXED_INTENT_CONFLICT,
    INCIDENT_HOLD,
    AUTHORIZED_NEW_ORDER_REQUIRED,
    TERMINATION_EVIDENCE_INSUFFICIENT,
}

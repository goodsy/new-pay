package com.paycore.payment.payment.application.provided

/**
 * confirm 업무 거절 저장 code(AUTH-001 §4.8·§4.9 — 영속 값, V11 CHECK). 저장되고 같은 키로 재생된다. 한도 정책 없음·허용 판정 불가·Route 없음은 거절이 아니라 일시 불가(저장 없음 —
 * D-AUTH1-001)라 여기 없다. API code 대응: INCIDENT_HOLD → ORDER_ON_HOLD, AUTHORIZED_NEW_ORDER_REQUIRED → ORDER_ALREADY_AUTHORIZED, 나머지 같은 이름.
 */
enum class ConfirmRejection {
    CONFIRM_CONTENT_CONFLICT,
    AUTHENTICATION_NOT_VERIFIED,
    PAYMENT_PLAN_NOT_ALLOWED,
    PAYMENT_NOT_ELIGIBLE,
    LIMIT_EXCEEDED,
    INCIDENT_HOLD,
    AUTHORIZED_NEW_ORDER_REQUIRED,
    PAYMENT_TERMINATED,
}

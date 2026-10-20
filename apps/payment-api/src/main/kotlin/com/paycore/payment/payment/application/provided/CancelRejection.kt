package com.paycore.payment.payment.application.provided

/**
 * 가맹점 취소 업무 거절 저장 code(CANCEL-001 §4.8·§4.9 — 영속 값, V20 CHECK). 저장되고 같은 키로 재생된다. Route·Capability 판정 불가는 거절이 아니라 일시 불가(503, 저장 없음)라 여기
 * 없다. API code는 같은 이름이다.
 */
enum class CancelRejection {
    CANCEL_REQUEST_CONFLICT,
    PAYMENT_NOT_CANCELLABLE,
    ALREADY_FULLY_CANCELLED,
    CANCEL_IN_PROGRESS,
    ORDER_ON_HOLD,
    CANCEL_AMOUNT_EXCEEDS_BALANCE,
    CURRENCY_MISMATCH,
    PARTIAL_CANCEL_NOT_SUPPORTED,
}

/** 가맹점 취소 성공 저장 code(CANCEL-001 §4.8 — 영속 값). 새 시작이면 ACCEPTED, 다른 키·같은 merchantCancelId·같은 내용이면 기존 취소 참조. */
enum class CancelOutcome { ACCEPTED, EXISTING_CANCELLATION }

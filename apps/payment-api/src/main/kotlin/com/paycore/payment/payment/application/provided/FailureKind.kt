package com.paycore.payment.payment.application.provided

/** 기술 실패 분류(로그·지표 label — 낮은 cardinality). 응답 code는 실패 종류([CreatePaymentFailure] 하위 타입)로 정해진다. */
enum class FailureKind {
    LOCK_TIMEOUT,
    DEADLOCK,
    DB_UNAVAILABLE,
    RETRY_EXHAUSTED,
    TRANSACTION_UNAVAILABLE,
    UNSUPPORTED_FINGERPRINT_VERSION,
    IDENTIFIER_COLLISION,
    OUTCOME_UNKNOWN,
    OTHER,
}

/** 원인 예외가 자기 실패 분류를 알려준다(저장소 실패·내부 신호). */
interface FailureKindSource {
    val failureKind: FailureKind
}

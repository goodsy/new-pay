package com.paycore.payment.payment.domain

/**
 * 승인 계약(AUTH-CONTRACT-001)의 서버 생성 식별자. 생성은 application의 식별자 생성 Port 책임이고 domain은 빈 값·짝 없는 서로게이트·
 * 길이(64, 영속 컬럼 관례)만 거부한다. 입력 그대로 비교한다(정규화 없음 — MID·PaymentId와 같은 관례). 예외 메시지에 값을 넣지 않는다.
 */
private fun requireIdentifier(value: String, field: String) {
    require(value.isNotEmpty()) { "$field must not be empty" }
    require(value.length <= IDENTIFIER_MAX_LENGTH) { "$field must be at most $IDENTIFIER_MAX_LENGTH characters" }
    Utf8Text.requireWellFormed(value, field)
}

private const val IDENTIFIER_MAX_LENGTH = 64

/** Payment의 논리 승인(PaymentAuthorization) 식별자. Payment당 하나다. */
@JvmInline
value class AuthorizationId(val value: String) {
    init {
        requireIdentifier(value, "authorizationId")
    }
}

/** 기관 승인 실행 시도(AuthorizationAttempt) 식별자. 기관 요청 ID와 다르다(시도 §4). */
@JvmInline
value class AttemptId(val value: String) {
    init {
        requireIdentifier(value, "attemptId")
    }
}

/** 고객 카드 인증 세션 식별자. */
@JvmInline
value class AuthenticationSessionId(val value: String) {
    init {
        requireIdentifier(value, "authenticationSessionId")
    }
}

/** 확정 금융 사실(PaymentTransaction, D-AUTH-003) 식별자. FINANCIAL 업무 키가 된다. */
@JvmInline
value class PaymentTransactionId(val value: String) {
    init {
        requireIdentifier(value, "paymentTransactionId")
    }
}

/** 운영자(사람) 식별자. hold 해제 2인 승인에서 요청자·승인자를 구분한다(D-AUTH-002). */
@JvmInline
value class OperatorId(val value: String) {
    init {
        requireIdentifier(value, "operatorId")
    }
}

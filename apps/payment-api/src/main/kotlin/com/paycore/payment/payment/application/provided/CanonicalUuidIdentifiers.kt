package com.paycore.payment.payment.application.provided

/** 멱등 요청 기록 ID(이벤트 causationId). 서버가 생성한 UUID(소문자 표준 표기). 만료 뒤 새 요청으로 갱신되면 새 ID를 쓴다. */
@JvmInline
value class IdempotencyRecordId(val value: String) {
    init {
        require(CanonicalUuid.matches(value)) { "idempotencyRecordId must be a canonical lower-case UUID" }
    }
}

/** 통합 이벤트 ID(CP-CONTRACT-001 §10 eventId). 서버가 생성한 UUID(소문자 표준 표기). 재발행 시 같은 값이다. */
@JvmInline
value class EventId(val value: String) {
    init {
        require(CanonicalUuid.matches(value)) { "eventId must be a canonical lower-case UUID" }
    }
}

private object CanonicalUuid {
    private val PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun matches(value: String): Boolean = PATTERN.matches(value)
}

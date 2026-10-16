package com.paycore.payment.payment.application.provided

/** 업무 결과가 아닌 기술 실패. 메시지에 요청 값을 넣지 않는다. [kind]·[diagnostic]은 로그용 분류다. */
sealed class CreatePaymentFailure(message: String, cause: Throwable?) : RuntimeException(message, cause) {
    abstract val kind: FailureKind

    /** 원인 사슬에서 찾은 저장소 진단 값(없으면 null). */
    val diagnostic: StoreDiagnostic?
        get() = generateSequence(cause) { t -> t.cause?.takeIf { it !== t } }.take(MAX_CAUSE_DEPTH)
            .filterIsInstance<StoreDiagnosticSource>().firstNotNullOfOrNull { it.diagnostic }

    /**
     * 멱등 예약 단계의 잠금 대기 timeout(IDEMPOTENCY_REQUEST_IN_PROGRESS, retryable). 저장 없음(TX 전체 rollback).
     * 대기 대상은 **같은 키의 미커밋 요청**뿐 아니라 **인접 범위 갭 잠금**일 수 있다(CP-DB-001 review L-1): 멱등 예약은
     * `INSERT … ON DUPLICATE KEY UPDATE`라 기존 키의 중복 경로가 그 인덱스 레코드에 next-key 잠금을 잡고(READ COMMITTED에서도 중복 검사
     * 잠금은 남는다), 그 앞 갭에 다른 키를 넣는 요청이 기다릴 수 있다. 어느 쪽이든 결과·금융 의미는 같다(저장 없음, 같은 키로 재시도).
     * 예약 뒤 단계(권리 head·control·Payment)의 timeout은 이것이 아니라 [TemporarilyUnavailable]이다.
     */
    class RequestInProgress(cause: Throwable) : CreatePaymentFailure("same idempotency key is still in progress", cause) {
        override val kind: FailureKind = FailureKind.LOCK_TIMEOUT
    }

    /**
     * 교착 재시도 소진·(예약 뒤) 잠금 timeout·DB 일시 오류·지원하지 않는 저장 fingerprint 버전·식별자 충돌 반복. 이 요청의 TX는 commit되지
     * 않았다(TEMPORARILY_UNAVAILABLE, 같은 키로 재시도).
     */
    class TemporarilyUnavailable(cause: Throwable) : CreatePaymentFailure("payment store temporarily unavailable", cause) {
        override val kind: FailureKind = kindOf(cause)
    }

    /** 서버가 준 새 식별자가 기존 값과 충돌했다(사용자 payload 충돌 아님). rollback됨 — 새 식별자로 같은 키 재시도. */
    class IdentifierCollision(val identifier: GeneratedIdentifier) : CreatePaymentFailure("generated identifier collision: $identifier", null) {
        override val kind: FailureKind = FailureKind.IDENTIFIER_COLLISION
    }

    /** commit 결과를 확인하지 못했다. 새 키를 만들지 말고 같은 키로 다시 요청해 결과를 확인한다(초안 §5). */
    class OutcomeUnknown(cause: Throwable) : CreatePaymentFailure("commit outcome unknown", cause) {
        override val kind: FailureKind = FailureKind.OUTCOME_UNKNOWN
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 20

        fun kindOf(cause: Throwable): FailureKind = when (cause) {
            is FailureKindSource -> cause.failureKind
            is IdentifierCollision -> FailureKind.IDENTIFIER_COLLISION
            else -> FailureKind.OTHER
        }
    }
}

/** 서버가 생성해 넘긴 식별자 종류([NewPaymentIdentifiers]). */
enum class GeneratedIdentifier { PAYMENT_ID, PAYMENT_KEY, EVENT_ID }

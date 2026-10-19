package com.paycore.payment.payment.application.provided

/**
 * confirm 실패(결과를 바꾸지 않음 — 같은 Idempotency-Key 재시도로 확인한다). 메시지에 요청 값·내부 값을 넣지 않는다.
 * - [RequestInProgress]: 같은 키의 앞 요청이 아직 커밋하지 않았다(409 IDEMPOTENCY_REQUEST_IN_PROGRESS).
 * - [TemporarilyUnavailable]: 허용 판정 불가·한도 정책 없음(D-AUTH1-001)·Route 프로파일 없음·저장소 일시 실패(503, 저장 없음). [reason]은 로그·지표 전용 고정 분류.
 * - [OutcomeUnknown]: TX1 commit 결과 불명(503 OUTCOME_UNCONFIRMED — 새 키 금지).
 */
sealed class ConfirmPaymentFailure(val reason: String, cause: Throwable?) : RuntimeException("confirm failure: $reason", cause, false, false) {
    class RequestInProgress(cause: Throwable?) : ConfirmPaymentFailure("REQUEST_IN_PROGRESS", cause)

    class TemporarilyUnavailable(reason: String, cause: Throwable? = null) : ConfirmPaymentFailure(reason, cause)

    class OutcomeUnknown(cause: Throwable?) : ConfirmPaymentFailure("OUTCOME_UNKNOWN", cause)
}

package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.PaymentId

/** 가맹점 취소 결과(CANCEL-001 §4.9). [Accepted.view]는 응답 시점의 취소 **현재** 상태(재생도 현재 상태). */
sealed interface CancelPaymentResult {
    data class Accepted(val view: CancellationView, val replayed: Boolean) : CancelPaymentResult

    data class Rejected(val rejection: CancelRejection, val paymentId: PaymentId, val replayed: Boolean) : CancelPaymentResult

    data object NotFound : CancelPaymentResult

    data object IdempotencyKeyReused : CancelPaymentResult
}

/**
 * 가맹점 취소 실패(결과를 바꾸지 않음 — 같은 Idempotency-Key 재시도로 확인한다). 메시지에 요청 값·내부 값을 넣지 않는다.
 * - [RequestInProgress]: 같은 키의 앞 요청이 아직 커밋하지 않았다(409 IDEMPOTENCY_REQUEST_IN_PROGRESS).
 * - [TemporarilyUnavailable]: Route·취소 Capability 판정 불가(원 경로 불일치 — m-4 포함)·저장소 일시 실패(503, 저장 없음). [reason]은 로그·지표 전용 고정 분류.
 * - [OutcomeUnknown]: TX1 commit 결과 불명(503 OUTCOME_UNCONFIRMED — 새 키 금지).
 */
sealed class CancelPaymentFailure(val reason: String, cause: Throwable?) : RuntimeException("cancel failure: $reason", cause, false, false) {
    class RequestInProgress(cause: Throwable?) : CancelPaymentFailure("REQUEST_IN_PROGRESS", cause)

    class TemporarilyUnavailable(reason: String, cause: Throwable? = null) : CancelPaymentFailure(reason, cause)

    class OutcomeUnknown(cause: Throwable?) : CancelPaymentFailure("OUTCOME_UNKNOWN", cause)
}

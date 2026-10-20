package com.paycore.payment.payment.application.provided

/**
 * 가맹점 취소(CANCEL-001 §4 — `POST /v1/payments/{paymentId}/cancel`). TX1(취소 시작) → (S6) TXd(전송 게이트) → TX 밖 기관 취소 → TX2(결과 반영)를 조정하고 취소의 **현재 상태**를
 * 돌려준다. 이 조정자는 TX를 열지 않는다(단계 서비스가 각자 로컬 TX를 소유). 실패는 [CancelPaymentFailure].
 */
interface CancelPaymentUseCase {
    fun cancel(command: CancelPaymentCommand): CancelPaymentResult
}

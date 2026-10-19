package com.paycore.payment.payment.application.provided

/**
 * confirm(AUTH-001 §4 — VERIFIED 인증 뒤 가맹점 서버의 승인 요청). TX1(승인 시작) → TXd(전송 게이트) → TX 밖 기관 호출 → TX2(결과 반영)를 조정하고 **현재 승인 상태**를 돌려준다.
 * 이 조정자는 TX를 열지 않는다(단계 서비스가 각자 로컬 TX를 소유 — 외부 호출을 끼는 Use Case 관례). 실패는 [ConfirmPaymentFailure].
 */
interface ConfirmPaymentUseCase {
    fun confirm(command: ConfirmPaymentCommand): ConfirmPaymentResult
}

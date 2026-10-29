package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8 rev 0.4 L-2 — 대상 FQN을 이 클래스로 바꾼 변형 규칙으로 검사): 기한 확인이 취소를 쓰고 잠그고 시작 단계 서비스에 의존한다. */
class DeadlineConfirmationStartingCancel(
    private val cancellations: com.paycore.payment.payment.application.required.PaymentCancellationRepository,
    private val start: com.paycore.payment.payment.application.CancelStartService,
) {
    fun call(id: com.paycore.payment.payment.domain.CancellationId) = cancellations.lock(id) to start.javaClass
}

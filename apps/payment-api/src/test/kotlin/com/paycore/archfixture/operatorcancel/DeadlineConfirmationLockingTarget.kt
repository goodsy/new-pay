package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8 rev 0.4 L-2 + review L-1 — 대상 FQN을 이 클래스로 바꾼 변형 규칙으로 검사): 기한 확인이 주문 제어 저장소에 의존한다(대상 잠금 재료). */
class DeadlineConfirmationLockingTarget(private val controls: com.paycore.payment.payment.application.required.OrderPaymentControlRepository) {
    fun name(): String = controls.javaClass.simpleName
}

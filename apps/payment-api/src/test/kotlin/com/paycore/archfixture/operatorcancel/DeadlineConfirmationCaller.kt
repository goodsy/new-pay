package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8 rev 0.4 L-1): 진입 컨트롤러가 아닌 클래스가 기한 확인 Use Case를 부른다. */
class DeadlineConfirmationCaller(private val useCase: com.paycore.payment.payment.application.provided.ConfirmOperatorApprovalDeadlineUseCase) {
    fun call(c: com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand) = useCase.confirm(c)
}

package com.paycore.archfixture.operatorcancel.web.admin

/** ArchUnit 위반 fixture(§4.8): 진입 컨트롤러와 **같은 단순 이름·다른 FQN**의 클래스가 운영자 보정 취소 Use Case를 부른다(정확 FQN 허용 확인). */
class AdminOperatorCancellationController(private val useCase: com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase) {
    fun handle(c: com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand) = useCase.cancel(c)
}

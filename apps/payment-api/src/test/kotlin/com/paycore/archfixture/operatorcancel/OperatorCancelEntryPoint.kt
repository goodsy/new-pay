package com.paycore.archfixture.operatorcancel

import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 운영자 보정 취소 Use Case를 부르는 진입점을 흉내 낸다 — 인증된 ADMIN 진입 adapter(정확 FQN) 밖의 클래스는 부르면 안 된다
 * (ADMIN-PAYMENT-OPS-001 §4.8 — 여전히 위반).
 */
class OperatorCancelEntryPoint(private val useCase: OperatorCorrectionCancelUseCase) {
    fun handle(command: OperatorCorrectionCancelCommand): OperatorCorrectionCancelResult = useCase.cancel(command)
}

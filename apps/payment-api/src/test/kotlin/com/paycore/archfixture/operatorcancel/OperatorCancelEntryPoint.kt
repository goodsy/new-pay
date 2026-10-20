package com.paycore.archfixture.operatorcancel

import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 운영자 보정 취소 Use Case를 부르는 진입점을 흉내 낸다 — ADMIN-BOOT-001 전에는 어떤 main 클래스도 부르면 안 된다(D-CANCEL1-001, m-5).
 */
class OperatorCancelEntryPoint(private val useCase: OperatorCorrectionCancelUseCase) {
    fun handle(command: OperatorCorrectionCancelCommand): OperatorCorrectionCancelResult = useCase.cancel(command)
}

package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8): 운영자 조정 서비스를 거치지 않고 TX1 단계 서비스의 startOperator를 직접 부른다. */
class DirectStartOperatorCaller(private val start: com.paycore.payment.payment.application.CancelStartService) {
    fun call(c: com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand) = start.startOperator(c)
}

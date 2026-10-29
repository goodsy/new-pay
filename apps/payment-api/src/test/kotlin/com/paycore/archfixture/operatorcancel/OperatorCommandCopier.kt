package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8 rev 0.3 L-c): 생성자 대신 data class copy로 운영자 명령을 바꾼다. */
object OperatorCommandCopier {
    fun retarget(c: com.paycore.payment.payment.application.provided.OperatorCorrectionCancelCommand) = c.copy(paymentId = "pay_other")
}

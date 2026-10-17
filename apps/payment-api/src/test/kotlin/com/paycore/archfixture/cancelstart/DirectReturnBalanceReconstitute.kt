package com.paycore.archfixture.cancelstart

import com.paycore.payment.payment.domain.AuthorizationId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.PaymentId
import com.paycore.payment.payment.domain.PaymentTransactionId
import com.paycore.payment.payment.domain.ReturnBalance

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): persistence adapter 밖에서 반환 잔액을 복원한다. 대상 = ReturnBalance.Companion.reconstitute. */
class DirectReturnBalanceReconstitute {
    fun bypass(paymentId: PaymentId, authorizationId: AuthorizationId, tx: PaymentTransactionId, money: Money): ReturnBalance =
        ReturnBalance.reconstitute(paymentId, authorizationId, tx, money, emptyList(), 0)
}

package com.paycore.archfixture.cancelstart

import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.ReturnBalance

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 취소 시작 밖에서 반환 잔액을 연다. 대상 = ReturnBalance.Companion.open. */
class DirectReturnBalanceOpen {
    fun bypass(authorization: PaymentAuthorization): ReturnBalance = ReturnBalance.open(authorization)
}

package com.paycore.archfixture.cancelstart

import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.ReturnBalance

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 취소 시작 밖에서 반환을 예약한다(취소 operation 없이). 대상 = ReturnBalance.reserve. */
class DirectReturnReservation {
    fun bypass(balance: ReturnBalance, id: CancellationId, money: Money) {
        balance.reserve(id, money)
    }
}

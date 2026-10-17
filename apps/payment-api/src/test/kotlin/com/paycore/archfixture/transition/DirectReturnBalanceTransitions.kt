package com.paycore.archfixture.transition

import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.ReturnBalance

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): domain 밖에서 반환 예약을 소비·해제·FORCED 재기록한다. 대상: ReturnBalance.consume·release·
 * reoccupyAfterLateCancel.
 */
class DirectReturnBalanceTransitions {
    fun bypass(balance: ReturnBalance, id: CancellationId) {
        balance.consume(id)
        balance.release(id)
        balance.reoccupyAfterLateCancel(id)
    }
}

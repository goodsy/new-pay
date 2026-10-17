package com.paycore.archfixture.cancelresolution

import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 취소 결과 반영 밖에서 취소 사건 hold를 연다. 대상 = OrderPaymentControl.reportCancellationResultConflict. */
class DirectCancelHoldReport {
    fun bypass(control: OrderPaymentControl, payment: Payment) {
        control.reportCancellationResultConflict(payment)
    }
}

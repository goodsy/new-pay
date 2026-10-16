package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.OrderKey
import com.paycore.payment.payment.domain.PaymentId
import java.time.Instant

/** 늦은 성공(확정 거절/만료 뒤 승인 성공) 보고 → 주문 사건 보류(CP-DOMAIN-001 M-3). 같은 보고가 여러 번 와도 보류는 하나다. */
interface ReportSuccessAfterTerminationUseCase {
    fun report(orderKey: OrderKey, paymentId: PaymentId, reportedAt: Instant)
}

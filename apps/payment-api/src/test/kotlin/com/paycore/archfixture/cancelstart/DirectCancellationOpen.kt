package com.paycore.archfixture.cancelstart

import com.paycore.payment.payment.domain.CancelAttemptId
import com.paycore.payment.payment.domain.CancelInitiator
import com.paycore.payment.payment.domain.CancelScope
import com.paycore.payment.payment.domain.CancellationId
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.PaymentAuthorization
import com.paycore.payment.payment.domain.PaymentCancellation
import com.paycore.payment.payment.domain.ProviderRequestRef
import java.time.Instant

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 취소 시작 밖에서 취소 operation을 연다(반환 예약 없이). 대상 = PaymentCancellation.Companion.open. */
class DirectCancellationOpen {
    fun bypass(payment: Payment, authorization: PaymentAuthorization, money: Money, initiator: CancelInitiator, request: ProviderRequestRef, at: Instant) {
        PaymentCancellation.open(CancellationId("x"), payment, authorization, money, CancelScope.PARTIAL, initiator, CancelAttemptId("x"), request, "v", at)
    }
}

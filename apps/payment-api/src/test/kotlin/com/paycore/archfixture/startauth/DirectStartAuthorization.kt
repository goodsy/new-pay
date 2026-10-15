package com.paycore.archfixture.startauth

import com.paycore.payment.payment.domain.Payment

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): OrderPaymentControl을 거치지 않고 Payment.startAuthorization을 직접 부른다(L-2 우회).
 * ArchitectureSpec이 이 클래스만 import해 `paymentStartAuthorizationOnlyThroughControl` 규칙이 실패함을 확인한다.
 */
class DirectStartAuthorization {
    fun bypass(payment: Payment) {
        payment.startAuthorization()
    }
}

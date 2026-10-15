package com.paycore.archfixture.startauth

import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): AuthorizationStart를 거치지 않고 control.startAuthorization을 직접 부른다 — Attempt
 * 생성·세션 귀속·한도 점유 없이 AUTHORIZING이 된다(L-3 우회). `controlStartAuthorizationOnlyThroughAuthorizationStart`가 실패해야 한다.
 */
class DirectControlStartAuthorization {
    fun bypass(control: OrderPaymentControl, payment: Payment) {
        control.startAuthorization(payment)
    }
}

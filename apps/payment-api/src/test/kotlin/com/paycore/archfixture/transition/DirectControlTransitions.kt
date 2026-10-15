package com.paycore.archfixture.transition

import com.paycore.payment.payment.domain.IncidentHoldRelease
import com.paycore.payment.payment.domain.OrderPaymentControl
import com.paycore.payment.payment.domain.Payment

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): domain 밖에서 주문 승인 이력·hold 해제를 직접 바꾼다(한도 정리·Payment 전이와 짝이 깨짐).
 * 대상: OrderPaymentControl.recordAuthorized(internal), completeAuthorization, releaseIncidentHold.
 */
class DirectControlTransitions {
    fun bypass(control: OrderPaymentControl, payment: Payment, release: IncidentHoldRelease) {
        control.recordAuthorized(payment)
        control.completeAuthorization(payment)
        control.releaseIncidentHold(release)
    }
}

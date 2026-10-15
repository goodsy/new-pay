package com.paycore.archfixture.transition

import com.paycore.payment.payment.domain.Payment
import com.paycore.payment.payment.domain.TerminationEvidence

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): domain 밖(application 자리)에서 Payment 결과 전이를 직접 부른다 — 거래·주문 성공 이력·
 * 한도 정리 없이 AUTHORIZED/FAILED가 되거나 Attempt 근거 없이 종료된다. 대상(메서드마다 위반 1건): markUnknown, markAuthorized, fail, expire,
 * addTerminationEvidence.
 */
class DirectPaymentTransitions {
    fun bypass(payment: Payment) {
        payment.markUnknown()
        payment.markAuthorized()
        payment.fail(TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED)
        payment.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
        payment.addTerminationEvidence(TerminationEvidence.INSTITUTION_CONFIRMED_NO_EFFECT)
    }
}

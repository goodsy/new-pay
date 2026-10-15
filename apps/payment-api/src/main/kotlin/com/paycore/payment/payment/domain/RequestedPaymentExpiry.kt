package com.paycore.payment.payment.domain

import java.time.Instant

/**
 * 결제창 만료(D-AUTH-005, D-CPD-002 EXPIRED 근거 NOT_TRANSMITTED_AND_SESSION_DISCARDED의 유일한 출처 — 명세 §5.5). REQUESTED Payment는
 * 승인을 시작하지 않아 Attempt가 없다(미전송). 이 연산이 같은 Payment의 폐기되지 않은 세션(CREATED·IN_PROGRESS·미귀속 VERIFIED)을 모두
 * EXPIRED로 만든 뒤 Payment를 EXPIRED로 종료한다 — 근거의 "세션 폐기" 조건을 domain이 직접 만족시킨다.
 * 승인 시작과의 직렬화(control 행 잠금)는 application(AUTH-001)의 책임이다.
 */
object RequestedPaymentExpiry {
    fun expire(payment: Payment, sessionsOfPayment: List<AuthenticationSession>, now: Instant) {
        if (payment.status != PaymentStatus.REQUESTED) throw InvalidPaymentTransitionException(payment.id, payment.status, "expireRequested")
        require(sessionsOfPayment.all { it.binding.paymentId == payment.id }) { "sessions must belong to this payment" }
        check(sessionsOfPayment.none { it.isBound() }) { "a REQUESTED payment cannot have a bound authentication session" }
        sessionsOfPayment.filterNot { it.isDiscarded() }.forEach { it.expire(AuthenticationExpiryCause.PAYMENT_CLOSED, now) }
        payment.expire(TerminationEvidence.NOT_TRANSMITTED_AND_SESSION_DISCARDED)
    }
}

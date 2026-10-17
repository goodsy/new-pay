package com.paycore.archfixture.checkoutweb

import com.paycore.payment.payment.application.required.PaymentRepository

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 결제창 web 클래스가 required Port에 직접 의존한다(D-AUTHN-006 위반). 대상 = required.PaymentRepository. */
class CheckoutControllerUsingRequiredPort(private val payments: PaymentRepository) {
    fun touch(): Any = payments
}

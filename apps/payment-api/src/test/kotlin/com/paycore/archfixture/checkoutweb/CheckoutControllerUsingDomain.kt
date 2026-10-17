package com.paycore.archfixture.checkoutweb

import com.paycore.payment.payment.domain.PaymentId

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 결제창 web 클래스가 domain 값에 직접 의존한다(D-AUTHN-006 위반). 대상 = domain.PaymentId. */
class CheckoutControllerUsingDomain {
    fun show(raw: String): String = PaymentId(raw).value
}

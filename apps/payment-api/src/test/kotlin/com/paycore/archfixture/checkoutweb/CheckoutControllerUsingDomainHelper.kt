package com.paycore.archfixture.checkoutweb

import com.paycore.payment.payment.adapter.web.CreatePaymentRequestParser

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 결제창 web 클래스가 같은 adapter.web의 목록 밖 helper(domain 값을 만드는 CreatePayment 파서)를 거쳐 domain에 닿는다
 * (검토 #3 m-A). 대상 = CreatePaymentRequestParser.
 */
class CheckoutControllerUsingDomainHelper {
    fun parse(body: ByteArray): Any = CreatePaymentRequestParser.parse(body)
}

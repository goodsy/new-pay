package com.paycore.archfixture.startauth;

import com.paycore.payment.payment.domain.Payment;

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): Java 메서드 참조(`Payment::startAuthorization`)로 OrderPaymentControl을 우회한다
 * (review round1 m-2). 호출 명령이 아니라 메서드 참조 접근이므로 `callMethod` 규칙은 이것을 보지 못했다.
 */
public final class JavaReferenceStartAuthorization {
    public Runnable bypass(Payment payment) {
        return payment::startAuthorization;
    }
}

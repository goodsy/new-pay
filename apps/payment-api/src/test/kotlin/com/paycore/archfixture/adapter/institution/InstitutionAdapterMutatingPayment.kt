package com.paycore.archfixture.adapter.institution

import com.paycore.payment.payment.domain.Payment

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 기관(TCP) Adapter가 timeout을 받고 업무 상태를 직접 UNKNOWN으로 바꾼다. Adapter는 Port
 * 결과만 돌려주고 전이는 application Use Case가 한다(AC 3). `institutionAdapterDoesNotMutateDomain`이 실패해야 한다.
 */
class InstitutionAdapterMutatingPayment {
    fun onReadTimeout(payment: Payment) {
        payment.markUnknown()
    }
}

package com.paycore.archfixture.startauth

import com.paycore.payment.payment.domain.Payment

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): Kotlin 호출 가능 참조(`payment::startAuthorization`)로 OrderPaymentControl을 우회한다
 * (review round1 m-2).
 */
class KotlinReferenceStartAuthorization {
    fun bypass(payment: Payment): () -> Unit = payment::startAuthorization
}

package com.paycore.archfixture.statusquery

import com.paycore.payment.payment.application.provided.PaymentStatusQuery
import com.paycore.payment.payment.application.provided.PaymentStatusResult
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): web adapter가 아닌 클래스(예: 배치·다른 Use Case)가 가맹점 조회 Use Case를 부른다 — 결제 상태 조회는 공개 web 진입점 전용이다(CP-QUERY-001 검토 #1 n-5).
 */
class SchedulerUsingStatusQuery(private val query: PaymentStatusQuery) {
    fun poll(mid: MerchantAccountId, paymentId: String): PaymentStatusResult = query.find(Environment.TEST, mid, paymentId)
}

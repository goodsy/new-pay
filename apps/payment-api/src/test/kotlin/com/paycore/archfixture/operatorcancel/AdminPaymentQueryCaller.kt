package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다 — TXN-OPS-001 §3): 진입 컨트롤러가 아닌 클래스가 관리자 거래 조회를 부른다. */
class AdminPaymentQueryCaller(private val query: com.paycore.payment.payment.application.provided.AdminPaymentQuery) {
    fun call() = query.detail("pay_x")
}

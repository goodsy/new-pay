package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다 — ADMIN-PAYMENT-OPS-001 §4.8): 진입 컨트롤러가 아닌 클래스가 대상 조회를 부른다. */
class TargetQueryCaller(private val query: com.paycore.payment.payment.application.provided.OperatorCancelTargetQuery) {
    fun call() = query.find("pay_x")
}

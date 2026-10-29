package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8): 진입 컨트롤러가 아닌 클래스가 결정 조회를 부른다. */
class DecisionQueryCaller(private val query: com.paycore.payment.payment.application.provided.OperatorDecisionQuery) {
    fun call() = query.after(0)
}

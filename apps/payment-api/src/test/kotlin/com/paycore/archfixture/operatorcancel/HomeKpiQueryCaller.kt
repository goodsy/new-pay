package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다 — ADMIN-HOME-KPI-001 §5): 진입 컨트롤러가 아닌 클래스가 운영 홈 KPI 조회를 부른다. */
class HomeKpiQueryCaller(private val query: com.paycore.payment.payment.application.provided.HomeKpiQuery) {
    fun call() = query.today()
}

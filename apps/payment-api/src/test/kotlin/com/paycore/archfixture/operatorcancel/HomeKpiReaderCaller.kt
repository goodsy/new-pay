package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다 — ADMIN-HOME-KPI-001 §5): 조회 서비스가 아닌 클래스가 KPI 집계 Port를 직접 쓴다(asOf·정렬·실패 분류 우회). */
class HomeKpiReaderCaller(private val reader: com.paycore.payment.payment.application.required.HomeKpiReader) {
    fun call(window: com.paycore.payment.payment.domain.HomeKpiWindow) = reader.read(window)
}

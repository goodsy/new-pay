package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(ADMIN-HOME-KPI-001 §5): main 클래스가 운영 홈 KPI 진입 컨트롤러를 참조한다(서명 채널 우회 호출 경로). */
class HomeKpiControllerReferrer(private val controller: com.paycore.payment.payment.adapter.web.admin.AdminHomeKpiController) {
    fun name(): String = controller.javaClass.simpleName
}

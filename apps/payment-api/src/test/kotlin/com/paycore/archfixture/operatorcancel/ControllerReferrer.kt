package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8): main 클래스가 진입 컨트롤러를 참조한다(우회 호출 경로). */
class ControllerReferrer(private val controller: com.paycore.payment.payment.adapter.web.admin.OperatorDecisionController) {
    fun name(): String = controller.javaClass.simpleName
}

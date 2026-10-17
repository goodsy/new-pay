package com.paycore.archfixture.checkoutweb

import org.apache.catalina.connector.Connector

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): connector 구성이 아닌 결제창 클래스가 Tomcat connector API를 쓴다(검토 #3 m-A). */
class CheckoutControllerUsingTomcat {
    fun port(connector: Connector): Int = connector.localPort
}

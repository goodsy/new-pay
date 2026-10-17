package com.paycore.archfixture.checkoutweb

import java.sql.Connection

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 결제창 web 클래스가 JDK JDBC로 DB에 닿는다(검토 #4 n-1). 대상 = java.sql.Connection. */
class CheckoutControllerUsingSql {
    fun read(connection: Connection): Boolean = connection.isValid(1)
}

package com.paycore.archfixture.checkoutweb

import org.springframework.boot.jdbc.DataSourceBuilder

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 결제창 web 클래스가 Boot JDBC 지원으로 DataSource를 만든다(검토 #4 n-1 — Boot는 필요한 패키지만 허용). */
class CheckoutControllerUsingBootJdbc {
    fun build(): Any = DataSourceBuilder.create().url("jdbc:none").build()
}

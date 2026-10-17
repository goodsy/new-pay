package com.paycore.archfixture.checkoutweb

import org.springframework.jdbc.core.JdbcTemplate

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 결제창 web 클래스가 JDBC로 직접 DB에 닿는다(검토 #3 m-A). 대상 = JdbcTemplate. */
class CheckoutControllerUsingJdbc(private val jdbc: JdbcTemplate) {
    fun count(): Int? = jdbc.queryForObject("SELECT 1", Int::class.java)
}

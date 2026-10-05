// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.badwebjdbc.sample.adapter.web

import org.springframework.jdbc.core.JdbcTemplate

/** Controller 계층이 JdbcTemplate으로 직접 SQL을 실행한다. */
class JdbcEndpoint(private val jdbc: JdbcTemplate) {
    fun count(): Int = jdbc.queryForObject("select count(*) from orders", Int::class.java) ?: 0
}

// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.badqueryjdbc.sample.adapter.query

import javax.sql.DataSource

/** jOOQ 조회 adapter가 DataSource로 JDBC를 직접 쓴다(jOOQ DSLContext는 Boot가 구성하므로 필요 없다). */
class RawJdbcQuery(private val dataSource: DataSource) {
    fun count(): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("select count(*) from orders").use { statement ->
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }
}

// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.badbatchjdbc.sample.adapter.batch

import org.springframework.batch.infrastructure.item.database.JdbcCursorItemReader
import org.springframework.batch.infrastructure.item.database.builder.JdbcCursorItemReaderBuilder
import org.springframework.jdbc.core.RowMapper
import javax.sql.DataSource

/** Batch Step이 DataSource + JdbcCursorItemReader/RowMapper로 Application을 우회해 직접 읽는다. */
class JdbcReadingStep(private val dataSource: DataSource) {
    fun reader(): JdbcCursorItemReader<String> = JdbcCursorItemReaderBuilder<String>()
        .name("orders")
        .dataSource(dataSource)
        .sql("select id from orders")
        .rowMapper(RowMapper { rs, _ -> rs.getString(1) })
        .build()
}

package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.MasterDbAccounts
import com.paycore.master.column
import org.testcontainers.mysql.MySQLContainer

/**
 * 시험 전용 SQL 포획(BUSINESS-PROFILE-001 BIZ-1-3·BIZ-1-6): MySQL `performance_schema.events_statements_history_long`에서 **runtime 계정 연결이 실제로 보낸 SQL 문장**을 읽는다
 * (Hibernate 통계 설정을 바꾸지 않아 공유 시험 컨텍스트가 하나로 유지된다). root(일회용 컨테이너 계정)로 소비자를 켜고 비운 뒤 [capture] 블록의 문장만 돌려준다.
 * 블록 밖 다른 스레드(예: Relay)의 문장이 섞일 수 있으므로 호출자는 표 이름으로 거른다.
 */
class MasterSqlCapture(private val container: MySQLContainer) {

    fun <T> capture(block: () -> T): Pair<T, List<String>> {
        MasterDbAccounts.root(container).use { c ->
            c.createStatement().use { s ->
                s.execute("UPDATE performance_schema.setup_consumers SET ENABLED = 'YES' WHERE NAME = 'events_statements_history_long'")
                s.execute("TRUNCATE TABLE performance_schema.events_statements_history_long")
            }
        }
        val result = block()
        val statements = MasterDbAccounts.root(container).use { c ->
            c.column(
                "SELECT h.SQL_TEXT FROM performance_schema.events_statements_history_long h JOIN performance_schema.threads t ON t.THREAD_ID = h.THREAD_ID " +
                    "WHERE t.PROCESSLIST_USER = '${MasterDbAccounts.MASTER_RUNTIME_USER}' AND h.SQL_TEXT IS NOT NULL ORDER BY h.THREAD_ID, h.EVENT_ID",
            )
        }
        return result to statements
    }

    companion object {
        /** 사업자 하위 표·등록정보 표(Root 전용 경로가 건드리면 안 되는 표). */
        val PROFILE_TABLES = listOf("business_registration", "business_representative", "business_category", "business_address", "business_contact")

        fun touchesProfileTables(sql: String): Boolean = PROFILE_TABLES.any { sql.contains(it, ignoreCase = true) }
    }
}

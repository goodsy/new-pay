package com.paycore.simulator.acquirer.adapter.persistence

import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.sql.SQLException
import javax.sql.DataSource

/**
 * 기동 검사(ACQUIRING-001 2-E — `MerchantReceiptPrivilegeGuard`와 같은 방식): runtime 계정이 가상 매입사 append-only 원장을 UPDATE/DELETE하거나 Worker 진행 표의
 * 식별·계획 열을 UPDATE·행을 DELETE할 수 있으면 기동을 거부한다. `WHERE 1 = 0` 문장을 runtime 연결에서 실제로 실행해 본다(행 변경 없음, rollback) —
 * 1142(표 권한 없음) 또는 1143(열 권한 없음)이어야 통과다. 매입사 설정 여부와 무관하게 본다(표는 migration이 늘 만든다). information_schema 열 목록으로 append-only
 * 4표는 **모든 열**, 진행 표는 허용 4열(state·published_at·updated_at·version)을 뺀 모든 열을 탐침한다(리뷰 m-5·재리뷰 m-4 — 열 단위 부여도 빠지지 않게).
 *
 * 시점: 가장 이른 lifecycle phase(웹 서버·VAN TCP·SFTP 서버·Worker보다 먼저).
 */
@Component
class AcquirerLedgerPrivilegeGuard(private val dataSource: DataSource) : SmartLifecycle {

    @Volatile
    private var running = false

    override fun start() {
        verify()
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MIN_VALUE

    fun verify() {
        dataSource.connection.use { c ->
            val autoCommit = c.autoCommit
            c.autoCommit = false
            try {
                val granted = (DELETE_PROBES + columnProbes(c)).filter { sql ->
                    try {
                        c.createStatement().use { it.executeUpdate(sql) }
                        true
                    } catch (e: SQLException) {
                        if (e.errorCode != MYSQL_TABLE_ACCESS_DENIED && e.errorCode != MYSQL_COLUMN_ACCESS_DENIED) throw e
                        false
                    }
                }
                check(granted.isEmpty()) {
                    "the runtime account can change the append-only acquirer ledger (${granted.size} probes succeeded) — " +
                        "re-run docker/simulator-mysql/init/01-simulator-database.sh on this volume (ACQUIRING-001 2-E)"
                }
            } finally {
                c.rollback()
                c.autoCommit = autoCommit
            }
        }
    }

    /**
     * 열 단위 UPDATE 탐침: append-only 4표는 모든 열, 진행 표는 허용 4열을 뺀 모든 열(information_schema 열 목록 — 열이 늘어도 빠지지 않는다, 재리뷰 m-4).
     * 표마다 열이 보이지 않으면(표 권한 없음·이름 변경) 기동을 거부한다.
     */
    private fun columnProbes(c: java.sql.Connection): List<String> = (APPEND_ONLY_TABLES + WORK_ITEM).flatMap { table ->
        val columns = c.prepareStatement(
            "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
        ).use { st ->
            st.setString(1, table)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        check(columns.isNotEmpty()) { "$table columns are not visible to the runtime account" }
        columns.filter { table != WORK_ITEM || it !in WORK_ITEM_UPDATABLE }.map { col -> "UPDATE $table SET `$col` = `$col` WHERE 1 = 0" }
    }

    private companion object {
        const val WORK_ITEM = "acquirer_work_item"
        val APPEND_ONLY_TABLES = listOf("acquirer_received_file", "acquirer_record_decision", "acquirer_result_line", "acquirer_outbound_file")
        val WORK_ITEM_UPDATABLE = setOf("state", "published_at", "updated_at", "version")
        const val MYSQL_TABLE_ACCESS_DENIED = 1142
        const val MYSQL_COLUMN_ACCESS_DENIED = 1143
        val DELETE_PROBES = (APPEND_ONLY_TABLES + WORK_ITEM).map { "DELETE FROM $it WHERE 1 = 0" }
    }
}

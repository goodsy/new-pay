package com.paycore.simulator.merchant.adapter.persistence

import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.sql.SQLException
import javax.sql.DataSource

/**
 * 기동 검사(WEBHOOK-001 §9 — `VanLedgerPrivilegeGuard`와 같은 방식): runtime 계정이 가상 가맹점 수신 원장을 UPDATE/DELETE할 수 있으면 기동을 거부한다. `WHERE 1 = 0` 문장을
 * runtime 연결에서 실제로 실행해 본다(행 변경 없음, rollback) — 1142(권한 없음)여야 통과다.
 *
 * 시점: 모든 singleton과 VAN 원장 검사(SmartInitializingSingleton) **뒤**, 웹 서버·VAN TCP 서버가 열리기 **전**(가장 이른 lifecycle phase). 옛 볼륨(DB 단위 DML)에서는
 * VAN 원장 검사가 먼저 거부한다(기존 진단 메시지 유지).
 */
@Component
class MerchantReceiptPrivilegeGuard(private val dataSource: DataSource) : SmartLifecycle {

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
                val granted = PROBES.filter { sql ->
                    try {
                        c.createStatement().use { it.executeUpdate(sql) }
                        true
                    } catch (e: SQLException) {
                        if (e.errorCode != MYSQL_TABLE_ACCESS_DENIED) throw e
                        false
                    }
                }
                check(granted.isEmpty()) {
                    "the runtime account can UPDATE/DELETE the append-only merchant webhook receipt ledger (${granted.size} probes succeeded) — " +
                        "re-run docker/simulator-mysql/init/01-simulator-database.sh on this volume (WEBHOOK-001 §9)"
                }
            } finally {
                c.rollback()
                c.autoCommit = autoCommit
            }
        }
    }

    private companion object {
        const val MYSQL_TABLE_ACCESS_DENIED = 1142
        val PROBES = listOf(
            "UPDATE merchant_webhook_receipt SET received_at = received_at WHERE 1 = 0",
            "DELETE FROM merchant_webhook_receipt WHERE 1 = 0",
        )
    }
}

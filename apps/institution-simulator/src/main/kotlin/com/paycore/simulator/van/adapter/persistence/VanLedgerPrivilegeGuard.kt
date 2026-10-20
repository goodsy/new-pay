package com.paycore.simulator.van.adapter.persistence

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.stereotype.Component
import java.sql.SQLException
import javax.sql.DataSource

/**
 * 기동 검사(AUTH-001 rev 0.3 m-4): runtime 계정이 가상 VAN append-only 표(원장·조회 기록·증거 사용)를 UPDATE/DELETE할 수 있으면 기동을 거부한다 — 초기화 스크립트를
 * 다시 실행하지 않은 옛 Simulator 볼륨(runtime = DB 단위 DML)이 원장 증거가 약한 상태로 돌지 않게 한다. 권한은 role·DB·표 어느 경로로 받았든 MySQL이 문장 실행 때 판정하므로
 * `WHERE 1 = 0` 문장을 runtime 연결에서 실제로 실행해 본다(행 변경 없음, rollback): 1142(권한 없음)여야 통과다. Flyway migration 뒤(모든 singleton 생성 뒤)에 돈다.
 */
@Component
class VanLedgerPrivilegeGuard(private val dataSource: DataSource) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() = verify()

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
                    "the runtime account can UPDATE/DELETE the append-only van ledger tables or lookup log (${granted.size} probes succeeded) — " +
                        "re-run docker/simulator-mysql/init/01-simulator-database.sh on this volume (AUTH-001 m-4)"
                }
            } finally {
                c.rollback()
                c.autoCommit = autoCommit // 검토 #2 n-4: 풀 연결 상태 복원
            }
        }
    }

    private companion object {
        const val MYSQL_TABLE_ACCESS_DENIED = 1142
        val PROBES = listOf(
            "UPDATE van_ledger_entry SET created_at = created_at WHERE 1 = 0",
            "DELETE FROM van_ledger_entry WHERE 1 = 0",
            "UPDATE van_inquiry_log SET created_at = created_at WHERE 1 = 0",
            "DELETE FROM van_inquiry_log WHERE 1 = 0",
            "UPDATE van_evidence_use SET created_at = created_at WHERE 1 = 0",
            "DELETE FROM van_evidence_use WHERE 1 = 0",
            // 검토 #2 n-1: 예약은 삭제 금지, 인증 조회 기록은 append-only.
            "DELETE FROM van_pending_decision WHERE 1 = 0",
            "UPDATE lookup_log SET looked_up_at = looked_up_at WHERE 1 = 0",
            "DELETE FROM lookup_log WHERE 1 = 0",
            // CANCEL-001 §6.5: 취소 원장도 append-only 기관 금융 사실 원본.
            "UPDATE van_cancel_entry SET created_at = created_at WHERE 1 = 0",
            "DELETE FROM van_cancel_entry WHERE 1 = 0",
        )
    }
}

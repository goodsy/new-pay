package com.paycore.admin.support

import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.time.Duration
import java.time.Instant

/**
 * 시험 전용 "세 번째 연결"(S3 리뷰 M-3): 행 하나를 `FOR UPDATE`로 잡아 두고, 경합시킬 TX들이 **실제로 그 잠금을 기다리는지** `performance_schema.data_lock_waits`로
 * 확인한 뒤 놓는다. 경합 시험이 우연한 스케줄에 기대지 않게 한다. root 연결(performance_schema 조회 권한)이며 ADMIN DB 업무 권한 모델과 무관하다.
 * - [awaitWaiters]: 기본은 **이 holder TX가 막고 있는** 대기만 센다(S4 리뷰 nit). 다른 업무 TX가 막는 연쇄 대기는 `blockedByHolder = false`.
 * - [grantedRowLocksOfWaiters]: 지금 기다리는 TX들이 [table]에서 이미 쥔 행 잠금 수(S4 리뷰 m-7 — 잠금 순서를 결정적으로 단언: 오름차순이면 0).
 */
class RowLockHolder private constructor(private val container: MySQLContainer, private val connection: Connection) : AutoCloseable {

    private var trxId: Long? = null

    /** 행을 잡는다(잠금 획득까지 이 연결에서 동기). */
    fun lock(table: String, keyColumn: String, key: Any): RowLockHolder {
        connection.prepareStatement("SELECT 1 FROM $table WHERE $keyColumn = ? FOR UPDATE").use { s ->
            s.setObject(1, key)
            s.executeQuery().use { check(it.next()) { "no row to lock in $table" } }
        }
        // performance_schema.data_locks는 실시간이다(information_schema.innodb_trx는 0.1초 캐시라 방금 시작한 TX가 안 보일 수 있다).
        trxId = connection.prepareStatement(
            "SELECT l.ENGINE_TRANSACTION_ID FROM performance_schema.data_locks l JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID " +
                "WHERE t.PROCESSLIST_ID = CONNECTION_ID() LIMIT 1",
        ).use { s -> s.executeQuery().use { check(it.next()) { "holder transaction not visible" }; it.getLong(1) } }
        return this
    }

    /** 이 연결의 TX 안에서 쓰기(동시 커밋된 다른 TX를 흉내 — 커밋은 [release]). */
    fun update(sql: String, vararg args: Any?): Int = connection.prepareStatement(sql).use { s ->
        args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
        s.executeUpdate()
    }

    /** [table]의 행 잠금을 기다리는 TX 수가 [expected]가 될 때까지 기다린다(시간 초과면 실패). */
    fun awaitWaiters(table: String, expected: Int, blockedByHolder: Boolean = true, timeout: Duration = Duration.ofSeconds(20)) {
        val deadline = Instant.now().plus(timeout)
        var seen = -1
        while (Instant.now().isBefore(deadline)) {
            seen = waiters(table, if (blockedByHolder) trxId else null)
            if (seen == expected) return
            Thread.sleep(POLL_MS)
        }
        error("expected $expected transaction(s) waiting on $table${if (blockedByHolder) " behind the holder" else ""}, saw $seen")
    }

    /** 지금 [table] 잠금을 기다리는 TX들이 같은 표에서 이미 **쥐고 있는**(GRANTED) 행 잠금 수. */
    fun grantedRowLocksOfWaiters(table: String): Int = AdminDbAccounts.root(container, "performance_schema").use { c ->
        c.prepareStatement(
            "SELECT COUNT(*) FROM performance_schema.data_locks g " +
                "WHERE g.OBJECT_SCHEMA = ? AND g.OBJECT_NAME = ? AND g.LOCK_TYPE = 'RECORD' AND g.LOCK_STATUS = 'GRANTED' " +
                "AND g.ENGINE_TRANSACTION_ID IN (SELECT w.REQUESTING_ENGINE_TRANSACTION_ID FROM performance_schema.data_lock_waits w)",
        ).use { s ->
            s.setString(1, AdminDbAccounts.ADMIN_DATABASE)
            s.setString(2, table)
            s.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

    /** 잠금을 놓는다(쓰기가 있으면 커밋). */
    fun release(commit: Boolean = false) {
        if (commit) connection.commit() else connection.rollback()
    }

    override fun close() = connection.close()

    private fun waiters(table: String, blocker: Long?): Int = AdminDbAccounts.root(container, "performance_schema").use { c ->
        c.prepareStatement(
            "SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits w " +
                "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID " +
                "WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = ?" + (if (blocker != null) " AND w.BLOCKING_ENGINE_TRANSACTION_ID = ?" else ""),
        ).use { s ->
            s.setString(1, AdminDbAccounts.ADMIN_DATABASE)
            s.setString(2, table)
            if (blocker != null) s.setLong(3, blocker)
            s.executeQuery().use { it.next(); it.getInt(1) }
        }
    }

    companion object {
        private const val POLL_MS = 50L

        fun open(container: MySQLContainer): RowLockHolder =
            RowLockHolder(container, AdminDbAccounts.root(container).apply { autoCommit = false })
    }
}

package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.PaymentDbAccounts
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID

/**
 * 시험용 원시 JDBC 접근. 애플리케이션 pool과 **별도의 DB 연결**을 열어 다중 connection 경합·제약 행위·잠금 대기 관찰에 쓴다.
 * - [app]: PAYMENT runtime 계정(`paycore_payment`, 기본 role `paycore_payment_runtime`) — 애플리케이션 pool과 **같은 권한**(표 단위).
 *   PAYMENT-DBACCOUNT-001 전에는 컨테이너 기본 `test` 계정(DB ALL)이었다. 이제 시험의 직접 SQL도 runtime 권한 밖의 일을 하면 1142로 실패한다.
 * - [root]: 잠금 대기 관찰(performance_schema.data_lock_waits)·연결 종료(KILL)·권한 조회 전용. Testcontainers는 root 비밀번호를
 *   컨테이너 비밀번호와 같게 설정한다. 일회용 컨테이너 값이며 운영 DB/키가 아니다.
 */
class DbAccess(private val container: MySQLContainer) {

    val databaseName: String get() = PaymentDbAccounts.PAYMENT_DATABASE

    fun app(): Connection = PaymentDbAccounts.connect(container, databaseName, PaymentDbAccounts.PAYMENT_RUNTIME_USER)

    fun root(): Connection = PaymentDbAccounts.root(container)

    /** 초기화 스크립트가 만든 계정(일회용 비밀번호)으로 접속한다(예: migration 계정의 GRANT 거부 시험). */
    fun connectAs(user: String, database: String = databaseName): Connection = PaymentDbAccounts.connect(container, database, user)

    fun <T> withApp(block: (Connection) -> T): T = app().use(block)

    fun <T> withRoot(block: (Connection) -> T): T = root().use(block)

    /** 단일 값 조회(없으면 null). */
    fun Connection.scalar(sql: String, vararg args: Any?): Any? =
        prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
        }

    fun Connection.rows(sql: String, vararg args: Any?): List<Map<String, Any?>> =
        prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                buildList {
                    while (rs.next()) {
                        add((1..meta.columnCount).associate { meta.getColumnLabel(it) to rs.getObject(it) })
                    }
                }
            }
        }

    fun Connection.update(sql: String, vararg args: Any?): Int =
        prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }

    /** 같은 연결에서 실행하고 MySQL vendor 오류 코드(성공이면 null)를 돌려준다. */
    fun Connection.errorCodeOf(sql: String, vararg args: Any?): Int? =
        try {
            update(sql, *args)
            null
        } catch (e: SQLException) {
            e.errorCode
        }

    fun count(sql: String, vararg args: Any?): Long = withApp { (it.scalar(sql, *args) as Number).toLong() }

    /** 잠금 관찰 전용 root 연결(autocommit). 관찰마다 새로 접속하면 인증 비용으로 관찰이 늦어져 대기 TX가 먼저 timeout될 수 있다. */
    private val probe: Connection by lazy { root() }

    /**
     * 지금 다른 TX의 잠금을 기다리는 InnoDB 트랜잭션 수(다른 연결이 실제로 DB 잠금에서 대기 중이라는 관찰 증거).
     * `performance_schema.data_lock_waits`(실시간)를 쓴다. `information_schema.INNODB_TRX`는 마지막 조회 후 0.1초 이상 지나야 갱신되는
     * 캐시라서 짧은 간격으로 조회하면 오래된 값이 계속 보인다(실제로 관찰 실패를 일으켰다).
     */
    @Synchronized
    fun lockWaitingTransactions(): Int =
        (probe.scalar("SELECT COUNT(DISTINCT REQUESTING_ENGINE_TRANSACTION_ID) FROM performance_schema.data_lock_waits") as Number).toInt()

    /** [expected]개 이상의 트랜잭션이 잠금 대기에 들어갈 때까지 기다린다(최대 [timeoutMillis]). 관찰 실패는 시험 실패다. */
    fun awaitLockWaiters(expected: Int, timeoutMillis: Long = 10_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (lockWaitingTransactions() >= expected) return
            Thread.sleep(10)
        }
        error("expected $expected lock-waiting transactions, observed ${lockWaitingTransactions()}")
    }

    companion object {
        /** 시험마다 겹치지 않는 식별자 접미사(같은 컨텍스트의 다른 spec·test와 행이 섞이지 않게 한다). */
        fun unique(prefix: String): String = prefix + "-" + UUID.randomUUID().toString().substring(0, 12)
    }
}

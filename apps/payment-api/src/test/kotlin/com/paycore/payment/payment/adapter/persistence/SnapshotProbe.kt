package com.paycore.payment.payment.adapter.persistence

import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.springframework.jdbc.datasource.DataSourceUtils
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * 시험 전용 jOOQ ExecuteListener(CP-QUERY-001 검토 #1 m-3 — 결정적 스냅샷 증거). 무장하면 SQL에 [marker]가 든 문장이 **끝난 직후**(같은 스레드·같은 TX 안, 다음 문장 전) 한 번:
 * (1) 그 TX의 연결로 `@@transaction_isolation`·`@@transaction_read_only`를 읽어 [observed]에 남기고, (2) [hook]을 동기로 실행한다(예: 다른 연결의 취소 TX2 커밋을 끝까지 기다림).
 * 무장하지 않으면 아무것도 하지 않는다(다른 시험 영향 없음). 운영 코드에는 없다.
 */
class SnapshotProbe(private val dataSource: DataSource) : ExecuteListener {

    data class TxState(val isolation: String, val readOnly: Long)

    private val armed = AtomicReference<Pair<String, () -> Unit>?>(null)
    val observed = AtomicReference<TxState?>(null)

    fun arm(marker: String, hook: () -> Unit) {
        observed.set(null)
        armed.set(marker to hook)
    }

    fun disarm() {
        armed.set(null)
        capturing.set(false)
    }

    /** P-1(EXPLAIN): 켜 두면 실행한 문장을 값이 들어간(inlined) SQL로 모은다. */
    private val capturing = java.util.concurrent.atomic.AtomicBoolean(false)
    val captured = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun capture() {
        captured.clear()
        capturing.set(true)
    }

    override fun executeEnd(ctx: ExecuteContext) {
        if (capturing.get()) ctx.query()?.let { captured.add(ctx.dsl().renderInlined(it)) }
        executeEndArmed(ctx)
    }

    private fun executeEndArmed(ctx: ExecuteContext) {
        val current = armed.get() ?: return
        val sql = ctx.sql() ?: return
        if (!sql.contains(current.first) || !armed.compareAndSet(current, null)) return
        val connection = DataSourceUtils.getConnection(dataSource)
        try {
            connection.prepareStatement("SELECT @@transaction_isolation, @@transaction_read_only").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    observed.set(TxState(rs.getString(1), rs.getLong(2)))
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
        current.second()
    }
}

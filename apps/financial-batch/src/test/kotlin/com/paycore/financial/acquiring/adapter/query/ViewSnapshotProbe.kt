package com.paycore.financial.acquiring.adapter.query

import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.ExecuteListenerProvider
import org.jooq.impl.DefaultExecuteListenerProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.datasource.DataSourceUtils
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource

/**
 * 시험 전용 jOOQ ExecuteListener(ACQ-VIEW-001 — payment-api `SnapshotProbe`와 같은 방식, 결정적 스냅샷 증거). 무장하면 SQL에 [marker]가 든 문장이 **끝난 직후**(같은 스레드·같은
 * TX 안, 다음 문장 전) 한 번: (1) 그 TX의 연결로 `@@transaction_isolation`·`@@transaction_read_only`를 읽어 [observed]에 남기고, (2) [hook]을 동기로 실행한다(예: 다른 연결의
 * 결과 반영 TX 커밋을 끝까지 기다림). [capture]를 켜면 실행한 문장을 값이 들어간 SQL로 모은다(질의 시간 상한 hint·EXPLAIN 확인). 운영 코드에는 없다.
 */
class ViewSnapshotProbe(private val dataSource: DataSource) : ExecuteListener {

    data class TxState(val isolation: String, val readOnly: Long)

    private val armed = AtomicReference<Pair<String, () -> Unit>?>(null)
    val observed = AtomicReference<TxState?>(null)
    private val capturing = AtomicBoolean(false)
    val captured = CopyOnWriteArrayList<String>()

    fun arm(marker: String, hook: () -> Unit) {
        observed.set(null)
        armed.set(marker to hook)
    }

    fun capture() {
        captured.clear()
        capturing.set(true)
    }

    fun disarm() {
        armed.set(null)
        capturing.set(false)
    }

    override fun executeEnd(ctx: ExecuteContext) {
        if (capturing.get()) ctx.query()?.let { captured.add(ctx.dsl().renderInlined(it)) }
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

@TestConfiguration(proxyBeanMethods = false)
class ViewSnapshotProbeConfig {
    @Bean
    fun viewSnapshotProbe(dataSource: DataSource): ViewSnapshotProbe = ViewSnapshotProbe(dataSource)

    /** Boot jOOQ 자동 구성이 ExecuteListenerProvider bean을 모은다. */
    @Bean
    fun viewSnapshotProbeListenerProvider(probe: ViewSnapshotProbe): ExecuteListenerProvider = DefaultExecuteListenerProvider(probe)
}

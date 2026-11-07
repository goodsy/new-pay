package com.paycore.financial.acquiring.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Semaphore

/**
 * ADMIN 채널 조회 bulkhead(ACQ-VIEW-001 §3.2): **대기 0** — permit이 없으면 즉시 busy(503 `ADMIN_READ_BUSY`)로 FINANCIAL 스레드·DB 연결(결제 사실 Inbox·매입 스케줄러)을 지킨다.
 * 지표 `paycore.financial.admin_channel.busy{endpoint}`.
 */
class AdminChannelPermits(settings: AdminChannelSettings, private val registry: MeterRegistry) {
    private val read = Semaphore(settings.readPermits)

    /** (ACQ-ADMIN-001) 조작 명령 permit — 조회 permit과 따로 센다(조회 폭주가 조작을, 조작이 조회를 막지 않는다). */
    private val operation = Semaphore(settings.operationPermits)

    fun <T> read(endpoint: String, busy: () -> T, block: () -> T): T = guarded(read, endpoint, busy, block)

    fun <T> operation(endpoint: String, busy: () -> T, block: () -> T): T = guarded(operation, endpoint, busy, block)

    private fun <T> guarded(permits: Semaphore, endpoint: String, busy: () -> T, block: () -> T): T {
        if (!permits.tryAcquire()) {
            registry.counter(BUSY_METRIC, "endpoint", endpoint).increment()
            return busy()
        }
        try {
            return block()
        } finally {
            permits.release()
        }
    }

    companion object {
        const val BUSY_METRIC = "paycore.financial.admin_channel.busy"
    }
}

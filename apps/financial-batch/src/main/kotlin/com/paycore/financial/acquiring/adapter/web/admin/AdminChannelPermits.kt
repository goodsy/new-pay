package com.paycore.financial.acquiring.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Semaphore

/**
 * ADMIN 채널 조회 bulkhead(ACQ-VIEW-001 §3.2): **대기 0** — permit이 없으면 즉시 busy(503 `ADMIN_READ_BUSY`)로 FINANCIAL 스레드·DB 연결(결제 사실 Inbox·매입 스케줄러)을 지킨다.
 * 지표 `paycore.financial.admin_channel.busy{endpoint}`.
 */
class AdminChannelPermits(settings: AdminChannelSettings, private val registry: MeterRegistry) {
    private val read = Semaphore(settings.readPermits)

    fun <T> read(endpoint: String, busy: () -> T, block: () -> T): T {
        if (!read.tryAcquire()) {
            registry.counter(BUSY_METRIC, "endpoint", endpoint).increment()
            return busy()
        }
        try {
            return block()
        } finally {
            read.release()
        }
    }

    companion object {
        const val BUSY_METRIC = "paycore.financial.admin_channel.busy"
    }
}

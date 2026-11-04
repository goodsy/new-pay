package com.paycore.master.merchant.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Semaphore

/**
 * ADMIN 채널 조회 bulkhead(MERCHANT-OPS-001 §3): **대기 0** — permit이 없으면 즉시 busy(503 `ADMIN_READ_BUSY`)로 MASTER 스레드·DB 연결(권리 발행·relay)을 지킨다.
 * 지표 `paycore.master.admin_channel.busy{endpoint}`.
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
        const val BUSY_METRIC = "paycore.master.admin_channel.busy"
    }
}

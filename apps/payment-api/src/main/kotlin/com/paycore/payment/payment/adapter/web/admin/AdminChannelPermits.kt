package com.paycore.payment.payment.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Semaphore

/**
 * ADMIN 채널 bulkhead(ADMIN-PAYMENT-OPS-001 §4.7 M-7): 관리 조회(대상·결정·목록 공용)와 명령(실행·기한 확인 공용 — rev 0.4 L-3) permit. **대기 0** — 없으면 즉시 busy(503)로 공개 승인 경로의
 * 스레드·DB 연결을 지킨다. 지표 `paycore.payment.admin_channel.busy{endpoint}`.
 */
class AdminChannelPermits(settings: AdminOpsSettings, private val registry: MeterRegistry) {
    private val read = Semaphore(settings.readPermits)
    private val command = Semaphore(settings.commandPermits)

    fun <T> read(endpoint: String, busy: () -> T, block: () -> T): T = guarded(read, endpoint, busy, block)

    fun <T> command(endpoint: String, busy: () -> T, block: () -> T): T = guarded(command, endpoint, busy, block)

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
        const val BUSY_METRIC = "paycore.payment.admin_channel.busy"
    }
}

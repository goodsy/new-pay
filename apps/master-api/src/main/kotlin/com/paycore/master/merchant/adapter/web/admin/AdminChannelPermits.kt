package com.paycore.master.merchant.adapter.web.admin

import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.Semaphore

/**
 * ADMIN 채널 bulkhead(MERCHANT-OPS-001 §3 조회, MERCHANT-OPS-002 §3.1 C-1 명령): **대기 0** — permit이 없으면 즉시 busy(조회 503 `ADMIN_READ_BUSY`, 명령 503 `ADMIN_WRITE_BUSY`)로
 * MASTER 스레드·DB 연결(권리 발행·relay)을 지킨다. 조회와 명령은 **별개 Semaphore**다 — 조회 폭주가 등록을 막거나 등록이 조회를 막지 않는다.
 * 지표 `paycore.master.admin_channel.busy{endpoint}`(세 종류 공용 — endpoint 이름이 다르다).
 * TXN-LIST-ENRICH-001 §3.6(M-1): 거래 목록 라벨 조회는 **세 번째 별개 Semaphore**([labels]) — 라벨 폭주가 목록·상세·승인 결정 조회(승인 회복 경로)의 조회 permit을 쓰지 않는다.
 */
class AdminChannelPermits(settings: AdminChannelSettings, private val registry: MeterRegistry) {
    private val read = Semaphore(settings.readPermits)
    private val write = Semaphore(settings.writePermits)
    private val labels = Semaphore(settings.labelPermits)

    fun <T> read(endpoint: String, busy: () -> T, block: () -> T): T = guarded(read, endpoint, busy, block)

    /** 명령 permit. 없으면 Use Case를 부르지 않고 [busy] — 재전송이 안전하다(효과 없음). */
    fun <T> write(endpoint: String, busy: () -> T, block: () -> T): T = guarded(write, endpoint, busy, block)

    /** 라벨 조회 permit(대기 0). 없으면 Query를 부르지 않고 [busy]. */
    fun <T> labels(endpoint: String, busy: () -> T, block: () -> T): T = guarded(labels, endpoint, busy, block)

    private fun <T> guarded(semaphore: Semaphore, endpoint: String, busy: () -> T, block: () -> T): T {
        if (!semaphore.tryAcquire()) {
            registry.counter(BUSY_METRIC, "endpoint", endpoint).increment()
            return busy()
        }
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }

    companion object {
        const val BUSY_METRIC = "paycore.master.admin_channel.busy"
    }
}

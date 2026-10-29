package com.paycore.admin.approval.application.required

import java.time.Instant

/**
 * 대사 checkpoint 단일 행 Port(ADMIN-PAYMENT-OPS-001 §4.11·§4.17 — runtime SELECT + 열 단위 UPDATE만, 행은 V5가 만든다). 호출자 TX 안에서만.
 * - [read]: 비잠금 읽기. [advance]: 잠근 뒤 `watermark = max(현재, 새 값)`(뒤로 가지 않는다)만. [markRun]: `last_run_at`만 — **완전 성공 실행**에서만 부른다(S7 리뷰 L-4,
 *   lag = 마지막 완전 성공 이후). [markFullScan]: `last_full_scan_at`만(watermark 불변 — 전체 스캔은 되돌리지 않는다).
 */
interface ReconciliationCheckpointStore {
    fun read(): ReconciliationCheckpoint

    fun advance(watermark: Long): ReconciliationCheckpoint

    fun markRun(now: Instant): ReconciliationCheckpoint

    fun markFullScan(now: Instant): ReconciliationCheckpoint
}

data class ReconciliationCheckpoint(val watermark: Long, val lastRunAt: Instant?, val lastFullScanAt: Instant?)

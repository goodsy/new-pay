package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant

/**
 * admin_ops_reconciliation_checkpoint 단일 행(id = 1 — V5가 만든다, runtime INSERT·DELETE 없음). 변경 열 = V6 열 단위 UPDATE(`watermark, last_run_at,
 * last_full_scan_at, version` — `AdminPersistenceMappingSpec` 대조). `@Version`: 두 대사 인스턴스의 checkpoint 갱신 충돌 감지.
 */
@Entity
@Table(name = "admin_ops_reconciliation_checkpoint")
class JpaReconciliationCheckpointEntity protected constructor() {
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Byte = 1
        protected set

    @field:Column(name = "watermark", nullable = false)
    var watermark: Long = 0

    @field:Column(name = "last_run_at")
    var lastRunAt: Instant? = null

    @field:Column(name = "last_full_scan_at")
    var lastFullScanAt: Instant? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean = other is JpaReconciliationCheckpointEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "JpaReconciliationCheckpointEntity(watermark=$watermark)"
}

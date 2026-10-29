package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.ReconciliationCheckpoint
import com.paycore.admin.approval.application.required.ReconciliationCheckpointStore
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [ReconciliationCheckpointStore] JPA 구현(§4.17): 단일 행(id = 1)을 갱신할 때만 `refresh(PESSIMISTIC_WRITE)`로 잠그고(§4.14 "대사: checkpoint만") 변경 열만 바꾼다
 * (dirty checking — 열 단위 권한 `watermark, last_run_at, last_full_scan_at, version`). watermark는 뒤로 가지 않는다(`max`) — 두 인스턴스가 경쟁해도 앞선 값이 남는다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaReconciliationCheckpointStore(private val entityManager: EntityManager) : ReconciliationCheckpointStore {

    override fun read(): ReconciliationCheckpoint = ApprovalPersistenceTranslation.translate { view(row()) }

    override fun advance(watermark: Long): ReconciliationCheckpoint = ApprovalPersistenceTranslation.translate {
        val row = locked()
        row.watermark = maxOf(row.watermark, watermark)
        entityManager.flush()
        view(row)
    }

    override fun markRun(now: Instant): ReconciliationCheckpoint = ApprovalPersistenceTranslation.translate {
        val row = locked()
        row.lastRunAt = now
        entityManager.flush()
        view(row)
    }

    override fun markFullScan(now: Instant): ReconciliationCheckpoint = ApprovalPersistenceTranslation.translate {
        val row = locked()
        row.lastFullScanAt = now
        entityManager.flush()
        view(row)
    }

    private fun row(): JpaReconciliationCheckpointEntity =
        requireNotNull(entityManager.find(JpaReconciliationCheckpointEntity::class.java, SINGLE_ROW)) { "reconciliation checkpoint row (V5)" }

    private fun locked(): JpaReconciliationCheckpointEntity = row().also { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }

    private fun view(r: JpaReconciliationCheckpointEntity) = ReconciliationCheckpoint(r.watermark, r.lastRunAt, r.lastFullScanAt)

    private companion object {
        const val SINGLE_ROW: Byte = 1
    }
}

package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.required.BusinessChangeEvidenceRecord
import com.paycore.master.merchant.application.required.BusinessChangeEvidenceStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** 1인 경로 근거 원문 INSERT 전용 저장(BIZ-CRIT-APPROVAL-001 R3-m2 — JPA 선택은 [JpaBusinessChangeEvidenceEntity]). 명령 TX 안에서만(MANDATORY), persist + flush. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBusinessChangeEvidenceStore(private val entityManager: EntityManager) : BusinessChangeEvidenceStore {
    override fun insert(record: BusinessChangeEvidenceRecord): Unit = translate(ENTITY) {
        entityManager.persist(
            JpaBusinessChangeEvidenceEntity.newRow(
                record.changeHistoryId, record.businessId.value, record.requestRecordId, record.evidenceRef.value, record.recordedBy.value, record.recordedAt,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val ENTITY = "BusinessChangeEvidence"
    }
}

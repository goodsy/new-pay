package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.ReconciliationFindingRecord
import com.paycore.admin.approval.application.required.ReconciliationFindingStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [ReconciliationFindingStore] JPA 구현: persist + flush — UNIQUE(kind, approval_id) 1062는 `DuplicateFinding`으로 분류(호출자 TX는 rollback). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaReconciliationFindingStore(private val entityManager: EntityManager) : ReconciliationFindingStore {
    override fun record(finding: ReconciliationFindingRecord) = ApprovalPersistenceTranslation.translate {
        entityManager.persist(JpaReconciliationFindingEntity.newRow(finding.kind.name, finding.approvalId, finding.paymentRef, finding.detail, finding.foundAt))
        entityManager.flush()
    }

    override fun openCounts(): Map<String, Long> = ApprovalPersistenceTranslation.translate {
        entityManager.createQuery("SELECT f.kind, COUNT(f) FROM JpaReconciliationFindingEntity f GROUP BY f.kind", Array<Any>::class.java).resultList
            .associate { (it[0] as String) to (it[1] as Number).toLong() }
    }
}

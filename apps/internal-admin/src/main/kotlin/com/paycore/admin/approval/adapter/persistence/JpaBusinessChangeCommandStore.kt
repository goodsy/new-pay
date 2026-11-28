package com.paycore.admin.approval.adapter.persistence

import com.paycore.admin.approval.application.required.BusinessChangeCommandStore
import com.paycore.admin.approval.domain.BusinessChangeRequestSnapshot
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [BusinessChangeCommandStore] JPA 구현(BIZ-CRIT-APPROVAL-001 — `@Immutable` Entity, persist + flush, merge 금지). 읽을 때 Domain 생성자가 형식을 다시 검사한다(저장 결함 fail-closed). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaBusinessChangeCommandStore(private val entityManager: EntityManager) : BusinessChangeCommandStore {
    override fun insert(command: BusinessChangeRequestSnapshot) = ApprovalPersistenceTranslation.translate {
        entityManager.persist(
            JpaBusinessChangeCommandEntity.newRow(
                command.approvalId, command.businessId, command.baseVersion, command.proposalId, command.contentDigest, command.changedFields.joinToString(","),
                command.representativeChanged, command.subjectCorrection, command.evidenceRef, command.createdAt,
            ),
        )
        entityManager.flush()
    }

    override fun find(approvalId: String): BusinessChangeRequestSnapshot? = ApprovalPersistenceTranslation.translate {
        entityManager.find(JpaBusinessChangeCommandEntity::class.java, approvalId)?.let { r ->
            BusinessChangeRequestSnapshot(
                r.approvalId, r.businessId, r.baseVersion, r.proposalId, r.contentDigest, r.changedFields.split(","), r.representativeChanged, r.subjectCorrection,
                r.evidenceRef, r.createdAt,
            )
        }
    }
}

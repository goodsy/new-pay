package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquisitionOpsDecisionStore
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionOpsDecision
import com.paycore.financial.acquiring.domain.AcquisitionOpsKind
import com.paycore.financial.acquiring.domain.AcquisitionOpsOrigin
import com.paycore.financial.acquiring.domain.AcquisitionOpsOutcome
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [AcquisitionOpsDecisionStore] 구현(JPA, MANDATORY TX, append-only — persist + flush만). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionOpsDecisionStore(private val entityManager: EntityManager) : AcquisitionOpsDecisionStore {

    override fun find(approvalId: String): AcquisitionOpsDecision? = translate(DECISION) {
        entityManager.createQuery("select d from JpaAcquisitionOpsDecisionEntity d where d.approvalId = :id", JpaAcquisitionOpsDecisionEntity::class.java)
            .setParameter("id", approvalId).resultList.singleOrNull()?.let { d ->
                AcquisitionOpsDecision(
                    approvalId = d.approvalId, kind = AcquisitionOpsKind.valueOf(d.kind), outcome = AcquisitionOpsOutcome.valueOf(d.decision),
                    rejectionCode = d.rejectionCode, targetRef = d.targetRef, resultFileId = d.resultFileId?.let(AcquisitionFileId::parse), contentHash = d.contentHash,
                    requestedBy = d.requestedBy, approvedBy = d.approvedBy, approvedAt = d.approvedAt, executeBefore = d.executeBefore, decidedAt = d.decidedAt,
                    origin = AcquisitionOpsOrigin.valueOf(d.origin),
                )
            }
    }

    override fun append(decision: AcquisitionOpsDecision): Unit = translate(DECISION) {
        entityManager.persist(
            JpaAcquisitionOpsDecisionEntity.newRow(
                approvalId = decision.approvalId, kind = decision.kind.name, decision = decision.outcome.name, rejectionCode = decision.rejectionCode,
                targetRef = decision.targetRef, resultFileId = decision.resultFileId?.value, contentHash = decision.contentHash, requestedBy = decision.requestedBy,
                approvedBy = decision.approvedBy, approvedAt = decision.approvedAt, executeBefore = decision.executeBefore, decidedAt = decision.decidedAt,
                origin = decision.origin.name,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val DECISION = "acquisition_ops_decision"
    }
}

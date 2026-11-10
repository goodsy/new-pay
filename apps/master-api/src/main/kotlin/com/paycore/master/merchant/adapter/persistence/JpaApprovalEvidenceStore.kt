package com.paycore.master.merchant.adapter.persistence

import com.paycore.master.merchant.adapter.persistence.PersistenceFailures.translate
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalDecidedVia
import com.paycore.master.merchant.application.provided.ApprovalOutcome
import com.paycore.master.merchant.application.required.ApprovalEvidenceRecord
import com.paycore.master.merchant.application.required.ApprovalEvidenceStore
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MasterRejection
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [ApprovalEvidenceStore] JPA 구현(MERCHANT-OPS-002 §9.3·§9.9). 호출자 TX 안에서만(MANDATORY — 실행기·결정 조회가 TX를 소유한다).
 *
 * [record]는 `persist` 직후 **즉시 `flush`**한다(review m-1): `persist`만 하면 PK(approvalId) 충돌이 commit 시점 flush에서야 드러나 실행기가 업무 경합으로 분류하지 못한다.
 * flush 안에서 1062를 [com.paycore.master.merchant.application.required.PersistenceFailure.UniqueConflict]
 * ([com.paycore.master.merchant.application.required.StoreConstraint.APPROVAL_EVIDENCE], businessKey = true)로 번역하면 실행기의 업무 유일키 재시도(새 TX)가 판정 3으로 수렴한다.
 * flush는 그때까지의 같은 TX 변경도 SQL로 보내지만 commit은 아니다(실패하면 같은 TX 전체 rollback).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaApprovalEvidenceStore(
    private val rows: ApprovalEvidenceJpaRepository,
    private val entityManager: EntityManager,
) : ApprovalEvidenceStore {

    override fun find(approvalId: String): ApprovalEvidenceRecord? = translate(ENTITY) {
        rows.findById(approvalId).orElse(null)?.let(::toRecord)
    }

    override fun record(record: ApprovalEvidenceRecord): Unit = translate(ENTITY) {
        val e = record.evidence
        entityManager.persist(
            JpaApprovalEvidenceEntity.newRow(
                e.approvalId, record.operation.code, record.requestRecordId, e.requestedBy.value, e.approvedBy.value, e.approvedAt, e.executeBefore,
                e.evidenceRef.value, e.fingerprint, record.outcome.name, record.resultCode?.name, record.resultReference, record.decidedVia.name, record.recordedAt,
            ),
        )
        entityManager.flush()
    }

    private fun toRecord(row: JpaApprovalEvidenceEntity) = ApprovalEvidenceRecord(
        evidence = ApprovalEvidence(
            row.approvalId, ActorRef(row.requestedBy), ActorRef(row.approvedBy), row.approvedAt, row.executeBefore, EvidenceRef(row.evidenceRef), row.approvalFingerprint,
        ),
        operation = AdminOperation.ofCode(row.operation),
        requestRecordId = row.requestRecordId,
        outcome = ApprovalOutcome.valueOf(row.outcome),
        resultCode = row.resultCode?.let(MasterRejection::valueOf),
        resultReference = row.resultReference,
        decidedVia = ApprovalDecidedVia.valueOf(row.decidedVia),
        recordedAt = row.recordedAt,
    )

    private companion object {
        const val ENTITY = "admin_approval_evidence"
    }
}

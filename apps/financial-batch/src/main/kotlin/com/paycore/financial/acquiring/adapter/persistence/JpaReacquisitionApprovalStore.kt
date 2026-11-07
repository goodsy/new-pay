package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.ReacquisitionApprovalStore
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.ApprovalChannel
import com.paycore.financial.acquiring.domain.ReacquisitionApproval
import com.paycore.financial.acquiring.domain.ReacquisitionNotRequired
import com.paycore.financial.acquiring.domain.ReacquisitionTargetStatus
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * [ReacquisitionApprovalStore] 구현(JPA, MANDATORY TX, append-only — persist + flush만). ACQ-ADMIN-001 B1: runtime INSERT는 V18이 연다(입력 경로 = ADMIN 조작 서명 채널,
 * D-ACQ-011·D-ACQA-001). 승인 행은 같은 TX에서 먼저 쓴 결정 행을 FK로 가리킨다.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaReacquisitionApprovalStore(private val entityManager: EntityManager) : ReacquisitionApprovalStore {

    override fun find(approvalId: String): List<ReacquisitionApproval> = translate(APPROVAL) {
        entityManager.createQuery(
            "select r from JpaAcquisitionReacquisitionApprovalEntity r where r.approvalId = :id order by r.fileId, r.seq",
            JpaAcquisitionReacquisitionApprovalEntity::class.java,
        ).setParameter("id", approvalId).resultList.map { r ->
            ReacquisitionApproval(
                approvalId = r.approvalId, fileId = AcquisitionFileId.parse(r.fileId), seq = r.seq, transactionId = r.transactionId,
                recordType = AcquisitionRecordType.valueOf(r.recordType), targetGeneration = r.targetGeneration, newGeneration = r.newGeneration,
                targetStatus = ReacquisitionTargetStatus.valueOf(r.targetStatus), reacquisitionOf = r.reacquisitionOf, requestedBy = r.requestedBy,
                approvedBy = r.approvedBy, reasonCode = r.reasonCode, evidenceRef = r.evidenceRef, channel = ApprovalChannel.valueOf(r.channel), recordedAt = r.recordedAt,
            )
        }
    }

    override fun latestGeneration(transactionId: String, recordType: AcquisitionRecordType): Int? = translate(APPROVAL) {
        (
            entityManager.createNativeQuery(
                "SELECT MAX(g) FROM (SELECT generation AS g FROM acquisition_file_record WHERE transaction_id = :txn AND record_type = :type " +
                    "UNION ALL SELECT new_generation AS g FROM acquisition_reacquisition_approval WHERE transaction_id = :txn AND record_type = :type) x",
            ).setParameter("txn", transactionId).setParameter("type", recordType.name).singleResult as Number?
            )?.toInt()
    }

    override fun append(approvals: List<ReacquisitionApproval>): Unit = translate(APPROVAL) {
        approvals.forEach { approval ->
            entityManager.persist(
                JpaAcquisitionReacquisitionApprovalEntity.newRow(
                    approvalId = approval.approvalId, fileId = approval.fileId.value, seq = approval.seq, transactionId = approval.transactionId,
                    recordType = approval.recordType.name, targetGeneration = approval.targetGeneration, newGeneration = approval.newGeneration,
                    targetStatus = approval.targetStatus.name, reacquisitionOf = approval.reacquisitionOf, requestedBy = approval.requestedBy,
                    approvedBy = approval.approvedBy, reasonCode = approval.reasonCode, evidenceRef = approval.evidenceRef, channel = approval.channel.name,
                    recordedAt = approval.recordedAt,
                ),
            )
        }
        entityManager.flush()
    }

    override fun isNotRequired(transactionId: String, recordType: AcquisitionRecordType): Boolean = translate(NOT_REQUIRED) {
        entityManager.find(
            JpaAcquisitionReacquisitionNotRequiredEntity::class.java, JpaAcquisitionReacquisitionNotRequiredEntity.Key(transactionId, recordType.name),
        ) != null
    }

    override fun appendNotRequired(records: List<ReacquisitionNotRequired>, decidedAt: Instant): Unit = translate(NOT_REQUIRED) {
        records.forEach {
            entityManager.persist(
                JpaAcquisitionReacquisitionNotRequiredEntity.newRow(
                    transactionId = it.approval.transactionId, recordType = it.approval.recordType.name, approvalId = it.approval.approvalId,
                    fileId = it.approval.fileId.value, seq = it.approval.seq, approvedAmountMinor = it.approvedAmountMinor, cancelledAmountMinor = it.cancelledAmountMinor,
                    currency = it.currency, decidedAt = decidedAt,
                ),
            )
        }
        entityManager.flush()
    }

    private companion object {
        const val APPROVAL = "acquisition_reacquisition_approval"
        const val NOT_REQUIRED = "acquisition_reacquisition_not_required"
    }
}

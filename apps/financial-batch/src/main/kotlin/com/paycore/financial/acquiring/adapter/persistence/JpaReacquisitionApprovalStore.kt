package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.ReacquisitionApprovalStore
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.ApprovalChannel
import com.paycore.financial.acquiring.domain.ReacquisitionApproval
import com.paycore.financial.acquiring.domain.ReacquisitionTargetStatus
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [ReacquisitionApprovalStore] 구현(JPA, MANDATORY TX, append-only). **이번 작업에서 runtime 계정은 이 표에 SELECT만 있다**(V13): [append]는 DB가 1142로 거부한다 —
 * 입력 경로(ACQ-ADMIN-001)가 INSERT 권한을 열기 전까지 승인 기록은 만들어지지 않는다(D-ACQ-011).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaReacquisitionApprovalStore(private val entityManager: EntityManager) : ReacquisitionApprovalStore {

    override fun find(approvalId: String): ReacquisitionApproval? = translate(APPROVAL) {
        entityManager.find(JpaAcquisitionReacquisitionApprovalEntity::class.java, approvalId)?.let { r ->
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

    override fun append(approval: ReacquisitionApproval): Unit = translate(APPROVAL) {
        entityManager.persist(
            JpaAcquisitionReacquisitionApprovalEntity.newRow(
                approvalId = approval.approvalId, fileId = approval.fileId.value, seq = approval.seq, transactionId = approval.transactionId,
                recordType = approval.recordType.name, targetGeneration = approval.targetGeneration, newGeneration = approval.newGeneration,
                targetStatus = approval.targetStatus.name, reacquisitionOf = approval.reacquisitionOf, requestedBy = approval.requestedBy,
                approvedBy = approval.approvedBy, reasonCode = approval.reasonCode, evidenceRef = approval.evidenceRef, channel = approval.channel.name,
                recordedAt = approval.recordedAt,
            ),
        )
        entityManager.flush()
    }

    private companion object {
        const val APPROVAL = "acquisition_reacquisition_approval"
    }
}

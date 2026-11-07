package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcceptedElsewhere
import com.paycore.financial.acquiring.application.required.AcquisitionTransmissionStore
import com.paycore.financial.acquiring.application.required.TransmissionAttemptRecord
import com.paycore.financial.acquiring.application.required.TransmissionHistoryEntry
import com.paycore.financial.acquiring.application.required.TransmissionOutcomeRecord
import com.paycore.financial.acquiring.application.required.TransmissionWithholdRecord
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.TransmissionOutcome
import com.paycore.financial.acquiring.domain.TransmissionState
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [AcquisitionTransmissionStore] 구현(JPA, MANDATORY TX). INSERT는 persist + 즉시 flush(PK·CHECK·FK 위반을 이 호출 안에서 분류). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionTransmissionStore(private val entityManager: EntityManager) : AcquisitionTransmissionStore {

    override fun nextAttemptNo(fileId: AcquisitionFileId): Int = translate(ATTEMPT) {
        val max = entityManager.createQuery(
            "select max(a.attemptNo) from JpaAcquisitionTransmissionAttemptEntity a where a.fileId = :fileId", Int::class.javaObjectType,
        ).setParameter("fileId", fileId.value).singleResult
        (max ?: 0) + 1
    }

    override fun appendAttempt(attempt: TransmissionAttemptRecord): Unit = translate(ATTEMPT) {
        entityManager.persist(
            JpaAcquisitionTransmissionAttemptEntity.newRow(
                attempt.fileId.value, attempt.attemptNo, attempt.resumeState.name, attempt.contentSha256, attempt.claimedAt, attempt.leaseUntil,
            ),
        )
        entityManager.flush()
    }

    override fun appendOutcome(outcome: TransmissionOutcomeRecord): Unit = translate(OUTCOME) {
        val o = outcome.outcome
        val s = outcome.superseded
        entityManager.persist(
            JpaAcquisitionTransmissionOutcomeEntity.newRow(
                fileId = outcome.fileId.value, attemptNo = outcome.attemptNo, outcome = if (s) SUPERSEDED else o.code, observed = if (s) o.code else null,
                evidence = (o as? TransmissionOutcome.Delivered)?.evidence?.name?.takeUnless { s },
                reason = when (o) {
                    is TransmissionOutcome.NotDelivered -> o.reason.name
                    is TransmissionOutcome.Unknown -> o.reason.name
                    else -> null
                }?.takeUnless { s },
                resultingState = outcome.resultingState.name, decidedAt = outcome.decidedAt,
            ),
        )
        entityManager.flush()
    }

    override fun history(fileId: AcquisitionFileId): List<TransmissionHistoryEntry> = translate(ATTEMPT) {
        val outcomes = entityManager.createQuery(
            "select o from JpaAcquisitionTransmissionOutcomeEntity o where o.fileId = :fileId", JpaAcquisitionTransmissionOutcomeEntity::class.java,
        ).setParameter("fileId", fileId.value).resultList.associateBy { it.attemptNo }
        entityManager.createQuery(
            "select a from JpaAcquisitionTransmissionAttemptEntity a where a.fileId = :fileId order by a.attemptNo", JpaAcquisitionTransmissionAttemptEntity::class.java,
        ).setParameter("fileId", fileId.value).resultList.map { a ->
            val o = outcomes[a.attemptNo]
            TransmissionHistoryEntry(
                a.attemptNo, TransmissionState.valueOf(a.resumeState), o?.outcome, o?.observed, o?.evidence, o?.reason, o?.resultingState?.let(TransmissionState::valueOf),
            )
        }
    }

    override fun acceptedInOtherGeneration(fileId: AcquisitionFileId): List<AcceptedElsewhere> = translate(WITHHOLD) {
        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(
            "SELECT r.seq, r.transaction_id, r.record_type, a.file_id, a.seq, a.generation FROM acquisition_file_record r " +
                "JOIN acquisition_accepted_record a ON a.transaction_id = r.transaction_id AND a.record_type = r.record_type " +
                "WHERE r.file_id = :fileId AND NOT (a.file_id = r.file_id AND a.seq = r.seq) ORDER BY r.seq",
        ).setParameter("fileId", fileId.value).resultList as List<Array<Any?>>
        rows.map {
            AcceptedElsewhere(
                (it[0] as Number).toInt(), it[1] as String, AcquisitionRecordType.valueOf(it[2] as String), AcquisitionFileId.parse(it[3] as String),
                (it[4] as Number).toInt(), (it[5] as Number).toInt(),
            )
        }
    }

    override fun appendWithholds(withholds: List<TransmissionWithholdRecord>): Unit = translate(WITHHOLD) {
        withholds.forEach { w ->
            val e = w.evidence
            entityManager.persist(
                JpaAcquisitionTransmissionWithholdEntity.newRow(
                    w.fileId.value, e.seq, e.transactionId, e.recordType.name, e.acceptedFileId.value, e.acceptedSeq, e.acceptedGeneration, w.priorState.name, w.withheldAt,
                ),
            )
        }
        entityManager.flush()
    }

    private companion object {
        const val WITHHOLD = "acquisition_transmission_withhold"
        const val ATTEMPT = "acquisition_transmission_attempt"
        const val OUTCOME = "acquisition_transmission_outcome"
        const val SUPERSEDED = "SUPERSEDED"
    }
}

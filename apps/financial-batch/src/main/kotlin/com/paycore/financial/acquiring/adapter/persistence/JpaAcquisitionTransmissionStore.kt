package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquisitionTransmissionStore
import com.paycore.financial.acquiring.application.required.TransmissionAttemptRecord
import com.paycore.financial.acquiring.application.required.TransmissionHistoryEntry
import com.paycore.financial.acquiring.application.required.TransmissionOutcomeRecord
import com.paycore.financial.acquiring.domain.AcquisitionFileId
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

    private companion object {
        const val ATTEMPT = "acquisition_transmission_attempt"
        const val OUTCOME = "acquisition_transmission_outcome"
        const val SUPERSEDED = "SUPERSEDED"
    }
}

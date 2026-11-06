package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquisitionRecordResultStore
import com.paycore.financial.acquiring.application.required.RecordRef
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.AcquisitionRecordType
import com.paycore.financial.acquiring.domain.RecordResult
import com.paycore.financial.acquiring.domain.RecordResultEntry
import com.paycore.financial.acquiring.domain.RecordResultSource
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [AcquisitionRecordResultStore] 구현(JPA, MANDATORY TX, append-only — persist + flush만, 읽기는 JPQL). 행 → Domain은 Domain 생성자를 다시 거친다. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionRecordResultStore(private val entityManager: EntityManager) : AcquisitionRecordResultStore {

    override fun entries(fileId: AcquisitionFileId): Map<Int, List<RecordResultEntry>> = translate(RESULT) {
        entityManager.createQuery(
            "select r from JpaAcquisitionRecordResultEntity r where r.fileId = :fileId order by r.seq, r.entryNo", JpaAcquisitionRecordResultEntity::class.java,
        ).setParameter("fileId", fileId.value).resultList.groupBy({ it.seq }, ::toEntry)
    }

    override fun entriesOf(records: Collection<RecordRef>): Map<RecordRef, List<RecordResultEntry>> = translate(RESULT) {
        val wanted = records.toSet()
        wanted.map { it.fileId }.distinct().flatMap { file ->
            entries(file).map { (seq, list) -> RecordRef(file, seq) to list }
        }.filter { it.first in wanted }.toMap()
    }

    override fun append(record: RecordRef, entry: RecordResultEntry, responseId: Long?): Unit = translate(RESULT) {
        entityManager.persist(
            JpaAcquisitionRecordResultEntity.newRow(
                fileId = record.fileId.value, seq = record.seq, entryNo = entry.entryNo, result = entry.result.name, observedResult = entry.observedResult?.name,
                reasonCode = entry.reasonCode, source = entry.source.name, responseId = responseId, recognizedAt = entry.recognizedAt,
            ),
        )
        entityManager.flush()
    }

    override fun acceptedRecordOf(transactionId: String, recordType: AcquisitionRecordType): RecordRef? = translate(ACCEPTED) {
        entityManager.find(JpaAcquisitionAcceptedRecordEntity::class.java, JpaAcquisitionAcceptedRecordEntity.Key(transactionId, recordType.name))
            ?.let { RecordRef(AcquisitionFileId.parse(it.fileId), it.seq) }
    }

    override fun markAccepted(record: RecordRef, transactionId: String, recordType: AcquisitionRecordType, generation: Int, entry: RecordResultEntry): Unit =
        translate(ACCEPTED) {
            require(entry.result == RecordResult.ACCEPTED) { "only an ACCEPTED entry marks the acquisition as accepted" }
            entityManager.persist(
                JpaAcquisitionAcceptedRecordEntity.newRow(transactionId, recordType.name, record.fileId.value, record.seq, entry.entryNo, generation, entry.recognizedAt),
            )
            entityManager.flush()
        }

    private fun toEntry(r: JpaAcquisitionRecordResultEntity) = RecordResultEntry(
        entryNo = r.entryNo, result = RecordResult.valueOf(r.result), observedResult = r.observedResult?.let(RecordResult::valueOf), reasonCode = r.reasonCode,
        source = RecordResultSource.valueOf(r.source), recognizedAt = r.recognizedAt,
    )

    private companion object {
        const val RESULT = "acquisition_record_result"
        const val ACCEPTED = "acquisition_accepted_record"
    }
}

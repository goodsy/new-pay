package com.paycore.simulator.acquirer.adapter.persistence

import com.paycore.simulator.acquirer.application.required.AcquirerReceiptStore
import com.paycore.simulator.acquirer.application.required.StoredReceipt
import com.paycore.simulator.acquirer.domain.AcceptedSale
import com.paycore.simulator.acquirer.domain.AcquirerReceipt
import com.paycore.simulator.acquirer.domain.FileRejectionReason
import com.paycore.simulator.acquirer.domain.JudgedRecord
import com.paycore.simulator.acquirer.domain.PlannedResultFile
import com.paycore.simulator.acquirer.domain.PlannedResultLine
import com.paycore.simulator.acquirer.domain.ReceiptOutcome
import com.paycore.simulator.acquirer.domain.ReceivedRecordType
import com.paycore.simulator.acquirer.domain.RecordResult
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [AcquirerReceiptStore] JPA 구현(MANDATORY TX, persist + flush만 — 수정·삭제 연산 없음, `@Immutable` Entity, DB 권한 SELECT·INSERT). 연관 매핑 없이 id 값으로만
 * 잇는다(결과 줄의 거래 ID·종류·금액은 판정 행 JPQL join).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquirerReceiptStore(private val entityManager: EntityManager) : AcquirerReceiptStore {

    override fun receipts(acquirerCode: String, fileId: String): List<StoredReceipt> = entityManager.createQuery(
        "select r from JpaAcquirerReceivedFileEntity r where r.acquirerCode = :acquirer and r.fileId = :file order by r.receiptOrdinal",
        JpaAcquirerReceivedFileEntity::class.java,
    ).setParameter("acquirer", acquirerCode).setParameter("file", fileId).resultList.map(::toStored)

    override fun receipt(receiptId: Long): StoredReceipt =
        toStored(checkNotNull(entityManager.find(JpaAcquirerReceivedFileEntity::class.java, receiptId)) { "acquirer receipt $receiptId not found" })

    override fun append(receipt: AcquirerReceipt): Long {
        val row = JpaAcquirerReceivedFileEntity.newRow(
            receipt.acquirerCode, receipt.fileId, receipt.ordinal, receipt.contentSha256, receipt.contentBytes, receipt.recordCount, receipt.outcome.name,
            receipt.reason?.name, receipt.receivedAt,
        )
        AcquirerPersistenceFailures.flushing(entityManager) { entityManager.persist(row) }
        return checkNotNull(row.receiptId)
    }

    override fun appendJudgements(receiptId: Long, acquirerCode: String, judged: List<JudgedRecord>) {
        if (judged.isEmpty()) return
        val decidedAt = receipt(receiptId).receipt.receivedAt
        AcquirerPersistenceFailures.flushing(entityManager) {
            judged.forEach { j ->
                entityManager.persist(
                    JpaAcquirerRecordDecisionEntity.newRow(
                        receiptId, j.record.seq, acquirerCode, j.record.type.name, j.record.transactionId, j.record.providerTransactionId, j.record.recordAmountMinor,
                        j.record.nettedCancelAmountMinor, j.judgement.result.name, j.judgement.reason, j.judgement.evidenceEntryId, j.judgement.authorizationEntryId,
                        j.scenario?.action?.name, decidedAt,
                    ),
                )
            }
        }
    }

    override fun appendResultLines(receiptId: Long, files: List<PlannedResultFile>) {
        val lines = files.flatMap { f -> f.lines.map { f.seq to it } }
        if (lines.isEmpty()) return
        AcquirerPersistenceFailures.flushing(entityManager) {
            lines.forEach { (seq, l) -> entityManager.persist(JpaAcquirerResultLineEntity.newRow(receiptId, seq, l.recordSeq, l.result.name, l.reason)) }
        }
    }

    override fun resultLines(receiptId: Long, resultSeq: Int): List<PlannedResultLine> = entityManager.createQuery(
        "select l.recordSeq, d.transactionId, d.recordType, l.result, l.reasonCode, d.recordAmountMinor " +
            "from JpaAcquirerResultLineEntity l, JpaAcquirerRecordDecisionEntity d " +
            "where l.receiptId = :receipt and l.resultSeq = :seq and d.receiptId = l.receiptId and d.recordSeq = l.recordSeq order by l.recordSeq",
        Array<Any?>::class.java,
    ).setParameter("receipt", receiptId).setParameter("seq", resultSeq).resultList.map { row ->
        PlannedResultLine(
            recordSeq = row[0] as Int, transactionId = row[1] as String, recordType = ReceivedRecordType.valueOf(row[2] as String),
            result = RecordResult.valueOf(row[3] as String), reason = row[4] as String?, recordAmountMinor = row[5] as Long,
        )
    }

    override fun transactionAccepted(acquirerCode: String, transactionId: String, type: ReceivedRecordType): Boolean = entityManager.createQuery(
        "select count(d) from JpaAcquirerRecordDecisionEntity d where d.acquirerCode = :acquirer and d.recordType = :type and d.acceptedTransactionId = :txn",
        Long::class.javaObjectType,
    ).setParameter("acquirer", acquirerCode).setParameter("type", type.name).setParameter("txn", transactionId).singleResult > 0

    override fun evidenceAccepted(acquirerCode: String, type: ReceivedRecordType, evidenceEntryId: String): Boolean = entityManager.createQuery(
        "select count(d) from JpaAcquirerRecordDecisionEntity d where d.acquirerCode = :acquirer and d.recordType = :type and d.acceptedEvidenceEntryId = :evidence",
        Long::class.javaObjectType,
    ).setParameter("acquirer", acquirerCode).setParameter("type", type.name).setParameter("evidence", evidenceEntryId).singleResult > 0

    override fun acceptedSale(acquirerCode: String, transactionId: String): AcceptedSale? = entityManager.createQuery(
        "select d from JpaAcquirerRecordDecisionEntity d where d.acquirerCode = :acquirer and d.recordType = 'SALE' and d.acceptedTransactionId = :txn",
        JpaAcquirerRecordDecisionEntity::class.java,
    ).setParameter("acquirer", acquirerCode).setParameter("txn", transactionId).resultList.singleOrNull()?.let { d ->
        AcceptedSale(d.transactionId, d.providerTransactionId, checkNotNull(d.authorizationEntryId))
    }

    override fun consumedCancels(acquirerCode: String, authorizationEntryId: String): Long = entityManager.createQuery(
        "select coalesce(sum(case when d.recordType = 'SALE' then d.nettedCancelAmountMinor else d.recordAmountMinor end), 0) " +
            "from JpaAcquirerRecordDecisionEntity d where d.acquirerCode = :acquirer and d.authorizationEntryId = :auth and d.decision = 'ACCEPTED'",
        Long::class.javaObjectType,
    ).setParameter("acquirer", acquirerCode).setParameter("auth", authorizationEntryId).singleResult

    private fun toStored(row: JpaAcquirerReceivedFileEntity) = StoredReceipt(
        checkNotNull(row.receiptId),
        AcquirerReceipt(
            row.acquirerCode, row.fileId, row.receiptOrdinal, row.contentSha256, row.contentBytes, row.recordCount, ReceiptOutcome.valueOf(row.outcome),
            row.reasonCode?.let(FileRejectionReason::valueOf), row.receivedAt,
        ),
    )
}

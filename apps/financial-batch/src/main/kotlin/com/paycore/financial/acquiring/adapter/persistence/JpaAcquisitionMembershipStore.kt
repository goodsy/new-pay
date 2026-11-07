package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquisitionMembershipStore
import com.paycore.financial.acquiring.application.required.CancelNotRequiredRecord
import com.paycore.financial.acquiring.application.required.ExclusionRecord
import com.paycore.financial.acquiring.application.required.NettingRecord
import com.paycore.financial.acquiring.application.required.RecordRef
import com.paycore.financial.acquiring.application.required.SaleMembership
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [AcquisitionMembershipStore] 구현(JPA, MANDATORY TX, append-only — persist + flush만). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionMembershipStore(private val entityManager: EntityManager) : AcquisitionMembershipStore {

    override fun decidedSaleTransactionIds(transactionIds: Collection<String>): Set<String> = translate(RECORD) {
        transactionIds.distinct().chunked(CHUNK).flatMap { chunk ->
            entityManager.createNativeQuery(
                "SELECT transaction_id FROM acquisition_file_record WHERE record_type = 'SALE' AND generation = 0 AND transaction_id IN (:ids) " +
                    "UNION SELECT transaction_id FROM acquisition_exclusion WHERE transaction_id IN (:ids)",
            ).setParameter("ids", chunk).resultList.map { it as String }
        }.toSet()
    }

    override fun insertNettings(nettings: List<NettingRecord>): Unit = translate(NETTING) {
        nettings.forEach {
            entityManager.persist(
                JpaAcquisitionNettingEntity.newRow(it.cancellationTransactionId, it.originalTransactionId, it.amountMinor, it.currency, it.fileId?.value, it.seq, it.decidedAt),
            )
        }
        entityManager.flush()
    }

    override fun insertExclusions(exclusions: List<ExclusionRecord>): Unit = translate(EXCLUSION) {
        exclusions.forEach {
            entityManager.persist(
                JpaAcquisitionExclusionEntity.newRow(
                    it.transactionId, it.acquirerCode, it.environment.name, it.reason.name, it.approvedAmountMinor, it.nettedAmountMinor, it.currency,
                    it.businessDate, it.decidedAt,
                ),
            )
        }
        entityManager.flush()
    }

    override fun saleMemberships(originalTransactionIds: Collection<String>): Map<String, SaleMembership> = translate(RECORD) {
        // ACQ-ADMIN-001 B1: 재매입(generation ≥ 1)이 있으면 **가장 높은 세대**가 원 SALE이다 — 재매입 뒤 받은 전송 후 취소는 새 세대의 결과로 판정한다
        // (원 세대 REJECTED로 "취소 매입 불요"를 쓰면 새 세대가 ACCEPTED될 때 그 취소가 매입사에 가지 않는다). 재매입 순액에 이미 들어간 취소는 실행 TX가
        // "판정 끝남"을 확인한 것들뿐이다(CANCELLATION_UNDECIDED 거절) — 이 경로로 다시 셀 취소가 없다.
        originalTransactionIds.distinct().chunked(CHUNK).flatMap { chunk ->
            entityManager.createQuery(
                "select r from JpaAcquisitionFileRecordEntity r where r.recordType = 'SALE' and r.transactionId in :ids",
                JpaAcquisitionFileRecordEntity::class.java,
            ).setParameter("ids", chunk).resultList
        }.groupBy { it.transactionId }.mapValues { (_, rows) ->
            val r = rows.maxBy { it.generation }
            SaleMembership(AcquisitionFileId.parse(r.fileId), r.toDomain())
        }
    }

    override fun cancelledAmounts(originalTransactionIds: Collection<String>): Map<String, Long> = translate(RECORD) {
        originalTransactionIds.distinct().chunked(CHUNK).flatMap { chunk ->
            entityManager.createNativeQuery(
                "SELECT original_transaction_id, SUM(record_amount_minor) FROM acquisition_file_record WHERE record_type = 'CANCEL' AND generation = 0 " +
                    "AND original_transaction_id IN (:ids) GROUP BY original_transaction_id",
            ).setParameter("ids", chunk).resultList.map { row ->
                val r = row as Array<*>
                (r[0] as String) to (r[1] as Number).toLong()
            }
        }.toMap()
    }

    override fun decidedCancellationIds(cancellationTransactionIds: Collection<String>): Set<String> = translate(NETTING) {
        cancellationTransactionIds.distinct().chunked(CHUNK).flatMap { chunk ->
            entityManager.createNativeQuery(
                "SELECT cancellation_transaction_id FROM acquisition_netting WHERE cancellation_transaction_id IN (:ids) " +
                    "UNION SELECT transaction_id FROM acquisition_file_record WHERE record_type = 'CANCEL' AND generation = 0 AND transaction_id IN (:ids) " +
                    "UNION SELECT cancellation_transaction_id FROM acquisition_cancel_not_required WHERE cancellation_transaction_id IN (:ids)",
            ).setParameter("ids", chunk).resultList.map { it as String }
        }.toSet()
    }

    override fun insertCancelNotRequired(records: List<CancelNotRequiredRecord>): Unit = translate(NOT_REQUIRED) {
        records.forEach {
            entityManager.persist(
                JpaAcquisitionCancelNotRequiredEntity.newRow(
                    it.cancellationTransactionId, it.originalTransactionId, it.originalFileId.value, it.originalSeq, it.amountMinor, it.currency, it.businessDate, it.decidedAt,
                ),
            )
        }
        entityManager.flush()
    }

    override fun nettingMismatches(fileId: AcquisitionFileId): Int = translate(NETTING) {
        // 레코드 상계액 ↔ 그 레코드를 가리키는 상계 기록 합, 그리고 이 lane·업무일 제외 기록의 승인액 ↔ 그 승인의 상계 기록(파일 없음) 합(대사 입력 무결성 — §16 NIT).
        val records = (
            entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM acquisition_file_record r LEFT JOIN (SELECT seq, SUM(amount_minor) AS s FROM acquisition_netting WHERE file_id = :file GROUP BY seq) n " +
                    "ON n.seq = r.seq WHERE r.file_id = :file AND ${AcquisitionNettingIntegritySql.GENERATION_ZERO_MISMATCH}",
            ).setParameter("file", fileId.value).singleResult as Number
            ).toInt()
        val exclusions = (
            entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM acquisition_exclusion e LEFT JOIN (SELECT original_transaction_id AS o, SUM(amount_minor) AS s FROM acquisition_netting " +
                    "WHERE file_id IS NULL GROUP BY original_transaction_id) n ON n.o = e.transaction_id " +
                    "WHERE e.acquirer_code = :acquirer AND e.environment = :environment AND e.business_date = :day AND e.netted_amount_minor <> COALESCE(n.s, 0)",
            ).setParameter("acquirer", fileId.acquirerCode).setParameter("environment", fileId.environment.name).setParameter("day", fileId.businessDate)
                .singleResult as Number
            ).toInt()
        records + exclusions
    }

    override fun reacquisitionNettingMismatches(fileId: AcquisitionFileId): Int = translate(NETTING) {
        (
            entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM acquisition_file_record r WHERE r.file_id = :file AND ${AcquisitionNettingIntegritySql.REACQUISITION_MISMATCH}",
            ).setParameter("file", fileId.value).singleResult as Number
            ).toInt()
    }

    override fun notRequiredCancellationsOf(original: RecordRef): Int = translate(NOT_REQUIRED) {
        (
            entityManager.createNativeQuery("SELECT COUNT(*) FROM acquisition_cancel_not_required WHERE original_file_id = :file AND original_seq = :seq")
                .setParameter("file", original.fileId.value).setParameter("seq", original.seq).singleResult as Number
            ).toInt()
    }

    private companion object {
        const val CHUNK = 500
        const val RECORD = "acquisition_file_record"
        const val NETTING = "acquisition_netting"
        const val EXCLUSION = "acquisition_exclusion"
        const val NOT_REQUIRED = "acquisition_cancel_not_required"
    }
}

package com.paycore.financial.acquiring.adapter.persistence

import com.paycore.financial.acquiring.adapter.persistence.AcquiringPersistenceFailures.translate
import com.paycore.financial.acquiring.application.required.AcquisitionBacklogCounts
import com.paycore.financial.acquiring.application.required.AcquisitionBacklogReader
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [AcquisitionBacklogReader] 구현 — JPA native 읽기 전용 count(Coordinator 결정 C-2: financial-batch jOOQ codegen 미도입). 각 count는 한 문장이고 Entity를 영속성
 * 컨텍스트에 올리지 않는다(BLOB 미조회). 레코드 현재 상태는 결과 행에서 계산한다(CONFLICT 행 있음 → CONFLICT, 결정 행 없이 UNKNOWN 행 → UNKNOWN, 행 없음 → 결과 대기 —
 * `RecordResultRule.status`와 같은 정의).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAcquisitionBacklogReader(private val entityManager: EntityManager) : AcquisitionBacklogReader {

    override fun read(): AcquisitionBacklogCounts = translate(FILE) {
        AcquisitionBacklogCounts(
            transmissionUnknown = count("SELECT COUNT(*) FROM acquisition_file WHERE transmission_state = 'TRANSMISSION_UNKNOWN'"),
            transmissionQuarantined = count("SELECT COUNT(*) FROM acquisition_file WHERE transmission_state = 'TRANSMISSION_QUARANTINED'"),
            transmissionWithheld = count("SELECT COUNT(*) FROM acquisition_file WHERE transmission_state = 'TRANSMISSION_WITHHELD'"),
            uploadFailedRepeated = count(
                "SELECT COUNT(*) FROM acquisition_file f WHERE f.transmission_state <> 'TRANSMITTED' AND (SELECT COUNT(*) FROM acquisition_transmission_outcome o " +
                    "WHERE o.file_id = f.file_id AND o.outcome = 'NOT_DELIVERED' AND o.reason = 'UPLOAD_FAILED') >= ${AcquisitionBacklogCounts.UPLOAD_FAILED_REPEAT}",
            ),
            recordsUnknown = count(
                "SELECT COUNT(*) FROM (SELECT file_id, seq FROM acquisition_record_result GROUP BY file_id, seq " +
                    "HAVING SUM(result = 'UNKNOWN') > 0 AND SUM(result IN ('ACCEPTED', 'REJECTED', 'CONFLICT')) = 0) u",
            ),
            recordsConflict = count("SELECT COUNT(DISTINCT file_id, seq) FROM acquisition_record_result WHERE result = 'CONFLICT'"),
            responsesQuarantined = count("SELECT COUNT(*) FROM acquisition_response WHERE disposition = 'QUARANTINED'"),
            receiptConflicts = count("SELECT COUNT(*) FROM acquisition_file WHERE receipt_state = 'RECEIPT_CONFLICT'"),
            nettingMismatches = count(
                "SELECT COUNT(*) FROM acquisition_file_record r LEFT JOIN (SELECT file_id, seq, SUM(amount_minor) AS s FROM acquisition_netting " +
                    "WHERE file_id IS NOT NULL GROUP BY file_id, seq) n ON n.file_id = r.file_id AND n.seq = r.seq " +
                    "WHERE ${AcquisitionNettingIntegritySql.GENERATION_ZERO_MISMATCH}",
            ) + count(
                "SELECT COUNT(*) FROM acquisition_exclusion e LEFT JOIN (SELECT original_transaction_id AS o, SUM(amount_minor) AS s FROM acquisition_netting " +
                    "WHERE file_id IS NULL GROUP BY original_transaction_id) n ON n.o = e.transaction_id WHERE e.netted_amount_minor <> COALESCE(n.s, 0)",
            ),
            // ACQ-ADMIN-001 rev 0.3(리뷰 MJ-1): 재매입 레코드(세대 ≥ 1)는 따로 센다 — 상계액 = 판정 끝난 취소 합(I-17).
            reacquisitionNettingMismatches = count(
                "SELECT COUNT(*) FROM acquisition_file_record r WHERE ${AcquisitionNettingIntegritySql.REACQUISITION_MISMATCH}",
            ),
            awaitingResults = count(
                "SELECT COUNT(*) FROM acquisition_file_record r JOIN acquisition_file f ON f.file_id = r.file_id WHERE f.transmission_state = 'TRANSMITTED' " +
                    "AND NOT EXISTS (SELECT 1 FROM acquisition_record_result x WHERE x.file_id = r.file_id AND x.seq = r.seq)",
            ),
            cancelNotRequiredOriginalConflict = count(
                "SELECT COUNT(*) FROM acquisition_cancel_not_required n WHERE EXISTS (SELECT 1 FROM acquisition_record_result x " +
                    "WHERE x.file_id = n.original_file_id AND x.seq = n.original_seq AND x.result = 'CONFLICT')",
            ),
        )
    }

    private fun count(sql: String): Long = (entityManager.createNativeQuery(sql).singleResult as Number).toLong()

    private companion object {
        const val FILE = "acquisition_file"
    }
}

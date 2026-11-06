package com.paycore.financial.acquiring.application.required

import com.paycore.financial.acquiring.application.provided.AcquisitionAttemptView
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSummary
import com.paycore.financial.acquiring.application.provided.AcquisitionHistory
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordView
import com.paycore.financial.acquiring.application.provided.AcquisitionResponseView
import java.time.Instant

/**
 * 매입 현황 조회 Port(ACQ-VIEW-001 — jOOQ CQRS 조회 측, 구현 `JooqAcquisitionFileViewReader`(adapter.query), **쓰기·잠금 없음**, 비권위). 모든 SELECT에 질의 시간 상한
 * (MySQL `MAX_EXECUTION_TIME`). 실패는 [com.paycore.financial.acquiring.application.provided.AcquisitionViewFailure].
 */
interface AcquisitionFileViewReader {
    /**
     * 최신 업무일순 최대 [rows]행(호출자가 limit + 1을 넘겨 다음 쪽을 판정한다). [overdueBefore] = 결과 마감 시각 기준점(now − 결과 마감) — `transmitted_at ≤ overdueBefore`인
     * AWAITING 전송 완료 파일이 RESULT_OVERDUE다. null이면(결과 마감 설정 없음) 그 사유를 계산하지 않는다. 한 읽기 전용 REPEATABLE READ TX.
     */
    fun search(criteria: AcquisitionFileSearchCriteria, overdueBefore: Instant?, rows: Int): List<AcquisitionFileSummary>

    /** 한 읽기 전용 REPEATABLE READ TX의 상세. 레코드는 [records] 조건으로 최대 [recordRows]행. 없으면 null. */
    fun detail(fileId: String, records: AcquisitionRecordSearchCriteria, recordRows: Int): AcquisitionFileDetailRows?

    /** 한 읽기 전용 REPEATABLE READ TX의 레코드 쪽(최대 [rows]행). 파일이 없으면 null. */
    fun records(fileId: String, criteria: AcquisitionRecordSearchCriteria, rows: Int): List<AcquisitionRecordView>?
}

/** 상세 읽기 결과(같은 스냅샷). */
data class AcquisitionFileDetailRows(
    val summary: AcquisitionFileSummary,
    val cutoffPolicyVersion: String,
    val cutoffAt: Instant,
    val contentSha256: String,
    val contentBytes: Int,
    val transmissionLeaseUntil: Instant?,
    val updatedAt: Instant,
    val attempts: AcquisitionHistory<AcquisitionAttemptView>,
    val responses: AcquisitionHistory<AcquisitionResponseView>,
    val records: List<AcquisitionRecordView>,
)

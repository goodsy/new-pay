package com.paycore.financial.acquiring.application.provided

import java.time.Instant

/**
 * 매입 적체·격리 현황(2-F — Observability·Reconciliation). DB count는 읽기 전용 TX 하나(JPA native — C-2), 대기 사유별 체류는 마지막 봉인이 판정한 값(대기는 기록하지
 * 않는다 — 이 프로세스의 마지막 봉인 기준, 재기동 뒤 첫 봉인 전에는 비어 있다). 업무 판정에 쓰지 않는다.
 */
interface ReadAcquisitionBacklogUseCase {
    fun backlog(): AcquisitionBacklog
}

/**
 * DB count(설명은 `AcquisitionBacklogCounts`)와 lane별 대기([waiting] — (매입사, 환경, 종류 SALE·CANCEL, 사유) → 건수·가장 오래된 사건 시각),
 * [cancelReconcile] = lane별 대사로 넘긴 전송 후 취소 수(마지막 봉인 기준).
 */
data class AcquisitionBacklog(
    val transmissionUnknown: Long,
    val transmissionQuarantined: Long,
    /** (ACQ-ADMIN-001 B2 — L-9) TRANSMISSION_WITHHELD 파일 수(대사 입력). */
    val transmissionWithheld: Long = 0,
    val uploadFailedRepeated: Long,
    val recordsUnknown: Long,
    val recordsConflict: Long,
    val responsesQuarantined: Long,
    val receiptConflicts: Long,
    val nettingMismatches: Long,
    val awaitingResults: Long,
    val cancelNotRequiredOriginalConflict: Long,
    /** (ACQ-ADMIN-001 rev 0.3 — 리뷰 MJ-1) 재매입 레코드 상계 무결성(I-17). */
    val reacquisitionNettingMismatches: Long,
    val waiting: List<WaitingEntry>,
    val cancelReconcile: Map<String, Int>,
    /** (rev 1.1 — 리뷰 n-6) 이 현황을 읽은 시각(매입 시계 AcquiringClock) — 대기 체류 = observedAt − oldestEventAt. */
    val observedAt: Instant,
)

/** 대기 한 묶음. [lane] = `{acquirerCode}/{environment}`, [kind] = SALE·CANCEL, [oldestEventAt] = 가장 오래된 승인·취소 시각(체류 계산 기준). */
data class WaitingEntry(val lane: String, val kind: String, val reason: String, val count: Int, val oldestEventAt: Instant)

package com.paycore.admin.approval.application.required

import java.time.Instant

/**
 * 대사 발견 조회 Port(jOOQ — ADMIN-PAYMENT-OPS-001 §5 CQRS, S8): 화면 목록·종류별 수·checkpoint 실행 시각. 비권위 읽기 — 조회 서비스만 쓴다(ArchitectureSpec Reader 규칙).
 * 발견 행은 code 열만 가진다(사유·근거 문구 없음).
 */
interface ReconciliationFindingListReader {
    /** 최근 발견부터 최대 [limit]행. */
    fun list(limit: Int): List<ReconciliationFindingRow>

    /** 종류별 발견 수(해결 절차는 LIVE Gate — 기록된 발견은 모두 열린 발견이다, I-127). */
    fun openCounts(): Map<String, Long>

    fun checkpoint(): ReconciliationCheckpointRow?
}

data class ReconciliationFindingRow(
    val findingId: Long,
    val kind: String,
    val approvalId: String,
    val paymentRef: String?,
    val detailCode: String?,
    val foundAt: Instant,
)

data class ReconciliationCheckpointRow(val lastRunAt: Instant?, val lastFullScanAt: Instant?)

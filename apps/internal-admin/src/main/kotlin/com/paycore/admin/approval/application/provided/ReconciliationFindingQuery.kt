package com.paycore.admin.approval.application.provided

import java.time.Instant

/**
 * 대사 발견 화면 조회(ADMIN-PAYMENT-OPS-001 §4.17·§4.20, S8): SECURITY_ADMIN·APPROVER(계정 현재 행 ACTIVE)만 — 그 밖은 null(403). 비권위 조회(jOOQ).
 * [ReconciliationOverview.lastResult]는 **이 인스턴스**의 마지막 대사 실행 결과(재기동 뒤 없음 — 권위 판단은 lag 지표, I-127).
 */
interface ReconciliationFindingQuery {
    fun overview(viewerActorId: String): ReconciliationOverview?
}

data class ReconciliationOverview(
    val openCounts: Map<String, Long>,
    val findings: List<ReconciliationFindingView>,
    val truncated: Boolean,
    val lastRunAt: Instant?,
    val lastFullScanAt: Instant?,
    val lastResult: String?,
)

/** 발견 한 행 — code 열만(사유·근거 문구 없음). */
data class ReconciliationFindingView(
    val findingId: Long,
    val kind: String,
    val approvalId: String,
    val paymentRef: String?,
    val detailCode: String?,
    val foundAt: Instant,
)

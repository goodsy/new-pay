package com.paycore.admin.approval.application.provided

/**
 * PAYMENT 결정 ↔ ADMIN 승인 대사(ADMIN-PAYMENT-OPS-001 §4.17, D-OPS-008 — `adapter.batch.OperatorDecisionReconciliationScheduler`가 부른다). 상태를 바꾸지 않는다
 * (경보·발견·감사만). 증분(checkpoint watermark부터 최대 N페이지 → watermark 전진) + 주기적 전체 스캔(seq 0부터 끝까지, watermark 불변 — 최종 안전망).
 */
interface ReconcileOperatorDecisionsUseCase {
    fun reconcile(): ReconciliationReport
}

/** [result] `OK`·`PAYMENT_UNAVAILABLE`, [scanned] 본 결정 수, [recorded] 새로 쓴 발견 수, [fullScan] 이번에 전체 스캔을 했는지. */
data class ReconciliationReport(val result: String, val scanned: Int, val recorded: Int, val fullScan: Boolean)

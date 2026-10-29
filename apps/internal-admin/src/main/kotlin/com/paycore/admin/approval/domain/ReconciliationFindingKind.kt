package com.paycore.admin.approval.domain

/**
 * 대사 발견 종류(ADMIN-PAYMENT-OPS-001 §4.17, D-OPS-008 — DB `admin_ops_reconciliation_finding.kind` CHECK와 같다). 선언 순서 = 판정 우선순위
 * (한 결정에 여럿이 맞으면 앞의 것 하나로 기록하고 나머지는 detail에). [MISSING_DECISION]은 전체 스캔에서만 판정한다.
 */
enum class ReconciliationFindingKind {
    ORPHAN_DECISION,
    DECISION_FOR_UNAPPROVED,
    FINGERPRINT_MISMATCH,
    STATE_DIVERGENCE,
    UNRESOLVED_WITH_DECISION,
    MISSING_DECISION,
}

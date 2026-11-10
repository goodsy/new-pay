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

    /**
     * 소유 서비스 결과를 확정할 수 없음(MERCHANT-OPS-002 C-28 — 기한 + 여유 뒤에도 결정 조회 NONE이고 기한 확인이 400·413·409). 회복 job이 승인당 1회 기록하고 자동 종결하지 않는다
     * (대사 job의 판정 대상이 아니다 — 위 6종의 우선순위와 무관).
     */
    OWNER_UNRESOLVABLE,
}

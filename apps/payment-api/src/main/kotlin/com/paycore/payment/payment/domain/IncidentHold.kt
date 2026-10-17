package com.paycore.payment.payment.domain

enum class IncidentReason {
    /** 확정 거절/만료로 종료된 Payment에 대해 승인 성공이 보고됐다(계약 가정 위반 사건, 확정 문서 §4). */
    SUCCESS_AFTER_TERMINATION,

    /**
     * 기관 결과가 요청 또는 이미 확정한 결과와 상충한다(D-AUTH-002·D-AUTH-008): 종결 전 승인의 조건 불일치 승인(금액·통화·Plan·cardType·
     * 부분 승인), 승인 확정 뒤 반대·다른 금액 결과. 상태는 덮어쓰지 않는다.
     */
    CONFLICTING_INSTITUTION_RESULT,

    /**
     * 취소 결과가 요청 또는 확정한 취소 결과와 상충하거나, 확정 거절한 취소에 늦은 성공이 왔다(D-CANCEL-006, CANCEL-CONTRACT-001 §4.6). 그 Payment의 추가
     * 가맹점 취소를 막는다(신규 승인 차단과 별개 의미). 해제 판정·FORCED 출구는 RECON-CONTRACT-001이 정하기 전까지 없다 — 해제 거절(fail-closed, review m-1).
     * 영속 CHECK 확장은 CANCEL-001.
     */
    CANCEL_RESULT_CONFLICT,
}

/**
 * 주문 사건 보류 기록(append-only, D-AUTH-002). 자동 보상취소는 없다. 해제는 [IncidentHoldRelease](2인 승인 + 재대사 MATCH)로만 하고
 * 보류 기록 자체는 지우지 않는다.
 */
data class IncidentHold(val paymentId: PaymentId, val reason: IncidentReason, val occurrence: Int = 1) {
    init {
        require(occurrence >= 1) { "hold occurrence must be positive" }
    }
}

package com.paycore.admin.approval.domain

/**
 * 승인 요청 상태(§4.7.2, ADMIN-PAYMENT-OPS-001 §4.9). `EXECUTING`·`EXECUTION_UNKNOWN`은 소유 서비스 종류(2-TX) 전용이며 **ADMIN 소유 종류는 도달할 수 없다**
 * ([ApprovalRequest]에 그 전이가 없고 재구성도 거부 — 판정표 시험이 고정, DB도 V5 `ck_…_owner_states`로 저장을 막는다). `APPROVED`는 쓰지 않는다(I-20 — 승인 TX1이
 * 곧바로 `EXECUTING`으로 간다, Δ-1).
 */
enum class ApprovalStatus(val terminal: Boolean) {
    PENDING(false),
    EXECUTING(false),
    EXECUTION_UNKNOWN(false),
    EXECUTED(true),
    REJECTED(true),
    WITHDRAWN(true),
    EXPIRED(true),
    INVALIDATED(true),
    OWNER_REJECTED(true),
    ;

    companion object {
        /** 소유 서비스 종류 전용 상태(ADMIN 소유 종류에는 나오면 안 된다). */
        val OWNER_SERVICE_ONLY = setOf(EXECUTING, EXECUTION_UNKNOWN)
    }
}

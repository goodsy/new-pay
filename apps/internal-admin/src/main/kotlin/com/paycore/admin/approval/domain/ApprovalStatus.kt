package com.paycore.admin.approval.domain

/**
 * 승인 요청 상태(§4.7.2). `APPROVED`·`EXECUTING`·`EXECUTION_UNKNOWN`은 소유 서비스 종류(2-TX) 전용이며 **ADMIN 소유 종류는 도달할 수 없다**
 * ([ApprovalRequest]에 그 전이가 없다 — 판정표 시험이 고정. DB CHECK도 지금은 저장을 막는다 — S2 리뷰 m-1).
 */
enum class ApprovalStatus(val terminal: Boolean) {
    PENDING(false),
    APPROVED(false),
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
        val OWNER_SERVICE_ONLY = setOf(APPROVED, EXECUTING, EXECUTION_UNKNOWN)
    }
}

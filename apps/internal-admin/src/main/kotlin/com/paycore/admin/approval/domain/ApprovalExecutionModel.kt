package com.paycore.admin.approval.domain

/**
 * 승인 종류의 실행 모델(ADMIN-PAYMENT-OPS-001 §4.9, M-6).
 * - [ADMIN_OWNED]: ADMIN이 소유한 명령(계정·역할 등) — 승인 TX 하나에서 판정·실행·종료(§4.7.3 단일 TX 경로).
 * - [OWNER_SERVICE]: 다른 서비스(PAYMENT)가 소유한 명령 — 승인 TX(→ `EXECUTING`) → TX 밖 서명 호출 → 결과 TX(2-TX). 단일 TX 경로는 이 종류를 거부한다(fail-closed).
 */
enum class ApprovalExecutionModel {
    ADMIN_OWNED,
    OWNER_SERVICE,
}

package com.paycore.payment.payment.application.provided

/**
 * 회복 회차 결과(AUTH-001 §6.1 — 지표용 건수, ID 없음). [claimed] 선점한 일정, [fenced] PREPARED fencing(FAILED PRE_TRANSMISSION), [leaseExpired] dispatch lease 만료 표시,
 * [inquiries] 보낸 조회, [concluded] 조회 결과로 확정(AUTHORIZED·FAILED)된 승인, [escalated] 대사 이관(상태 불변), [failed] 처리 중 실패(그 건만 rollback — 다음 회차 재시도).
 */
data class RecoveryRunResult(
    val claimed: Int,
    val fenced: Int,
    val leaseExpired: Int,
    val inquiries: Int,
    val concluded: Int,
    val escalated: Int,
    val failed: Int,
)

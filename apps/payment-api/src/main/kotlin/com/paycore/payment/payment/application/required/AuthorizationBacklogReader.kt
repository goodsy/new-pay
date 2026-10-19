package com.paycore.payment.payment.application.required

import java.time.Instant

/**
 * 승인 적체 수(비권위 — 관찰 gauge 전용, AUTH-001 §9 Observability). [oldestUnknownPreparedAt]은 OUTCOME_UNKNOWN Attempt 중 가장 이른 준비 시각(없으면 null).
 */
data class AuthorizationBacklogCounts(
    val unknownAttempts: Long,
    val oldestUnknownPreparedAt: Instant?,
    val escalatedOpenSchedules: Long,
    val activeIncidentHolds: Long,
)

/**
 * 승인 적체 조회 Port(CONVENTIONS §14 jOOQ 조회 `…Reader`): OUTCOME_UNKNOWN Attempt 수·가장 이른 준비 시각, 대사 이관됐고 아직 닫히지 않은 회복 일정 수,
 * 해제 기록이 없는 사건 보류 수. 읽기 전용 TX 하나. 판정에 쓰지 않는다(상태 변경은 JPA 잠금 재판정).
 */
interface AuthorizationBacklogReader {
    fun counts(): AuthorizationBacklogCounts
}

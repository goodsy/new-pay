package com.paycore.payment.payment.application.provided

/**
 * OUTCOME_UNKNOWN Attempt 수, 그중 가장 오래된 것의 연령(초 — 준비 시각부터, 없으면 0, 음수 없음), 대사 이관 뒤 아직 닫히지 않은 회복 일정 수, 해제 전 사건 보류 수
 * (ID 없음 — AUTH-001 §9 gauge 값).
 */
data class AuthorizationBacklog(val unknownAttempts: Long, val oldestUnknownAgeSeconds: Long, val escalatedOpenSchedules: Long, val activeIncidentHolds: Long)

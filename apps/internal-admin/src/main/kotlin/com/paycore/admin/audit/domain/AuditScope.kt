package com.paycore.admin.audit.domain

/**
 * 감사 조회 범위(D-ADMIN1-008, m-11): SECURITY_ADMIN·APPROVER는 [All], 나머지는 [Self] — 서버가 세션 actor로 채우고 요청 값은 무시한다.
 * [Self]는 SQL WHERE `actor_id = ?`로 강제된다(조회 adapter).
 */
sealed interface AuditScope {
    data object All : AuditScope
    data class Self(val actorId: String) : AuditScope
}

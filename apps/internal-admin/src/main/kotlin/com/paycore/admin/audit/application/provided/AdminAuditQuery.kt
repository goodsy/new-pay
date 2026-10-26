package com.paycore.admin.audit.application.provided

import java.time.Instant

/**
 * 감사 조회(D-ADMIN1-008, m-11). 범위는 서버가 정한다: [AuditViewer.canViewAll](SECURITY_ADMIN·APPROVER — 호출자가 권한 표로 계산)면 전체, 아니면 자기 기록만
 * (필터의 actorId는 무시). 결과를 돌려주기 **전에** 별도 작은 TX로 `AUDIT_VIEWED` 감사 1행(필터 요약)을 커밋하고, 그 기록이 실패하면 결과 없이
 * [AuditUnavailable]을 던진다(웹은 503).
 */
interface AdminAuditQuery {
    fun search(viewer: AuditViewer, filter: AuditFilter, correlationId: String, remoteIp: String?): List<AuditEntry>
}

/** 조회자(세션 actor). [canViewAll]은 `AdminPermission.allows(…, VIEW_ALL_AUDIT)` 결과 — audit 모듈은 account 역할을 모른다(의존 방향 account → audit). */
data class AuditViewer(val actorId: String, val displayName: String, val canViewAll: Boolean)

/** 감사 목록 한 행(조회 모델). */
data class AuditEntry(
    val eventId: Long,
    val occurredAt: Instant,
    val actorKind: String,
    val actorId: String?,
    val actorDisplayName: String?,
    val executorActorId: String?,
    val action: String,
    val targetActorId: String?,
    val resultCode: String,
    val approvalId: String?,
    val correlationId: String,
    val detail: String?,
    val reason: String? = null,
)

class AuditUnavailable(cause: Throwable) : RuntimeException("audit view could not be recorded; results are withheld", cause)

package com.paycore.admin.audit.application

import com.paycore.admin.audit.application.provided.AdminAuditQuery
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditEntry
import com.paycore.admin.audit.application.provided.AuditFilter
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.audit.application.provided.AuditUnavailable
import com.paycore.admin.audit.application.required.AdminAuditReader
import com.paycore.admin.audit.application.required.AuditClock
import com.paycore.admin.audit.domain.AuditScope
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.temporal.ChronoUnit

/**
 * [AdminAuditQuery](m-11): 범위 결정(서버) → 별도 로컬 TX로 `AUDIT_VIEWED` 커밋 → 그 뒤에만 조회. 감사 기록이 실패하면 결과 없이 [AuditUnavailable].
 * 조회 자체는 jOOQ Reader(비잠금 읽기, TX 밖)다.
 */
@Service
class AdminAuditQueryService(
    transactionManager: PlatformTransactionManager,
    private val recorder: AdminAuditRecorder,
    private val reader: AdminAuditReader,
    private val clock: AuditClock,
) : AdminAuditQuery {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)

    override fun search(viewer: com.paycore.admin.audit.application.provided.AuditViewer, filter: AuditFilter, correlationId: String, remoteIp: String?): List<AuditEntry> {
        val scope = if (viewer.canViewAll) AuditScope.All else AuditScope.Self(viewer.actorId)
        val effective = if (scope is AuditScope.Self) filter.copy(actorId = null) else filter
        try {
            transactions.execute({ false }) {
                recorder.record(
                    AuditRecord(
                        occurredAt = clock.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = viewer.actorId,
                        actorDisplayName = viewer.displayName, action = ACTION_AUDIT_VIEWED, resultCode = "SUCCESS", correlationId = correlationId,
                        remoteIp = remoteIp, detail = summary(scope, effective),
                    ),
                )
            }
        } catch (e: RuntimeException) {
            throw AuditUnavailable(e)
        }
        return reader.search(scope, effective)
    }

    /** `AUDIT_VIEWED` detail: 필터 요약(값 자체는 넣지 않는다 — 있음/없음·종류·크기만). */
    internal fun summary(scope: AuditScope, filter: AuditFilter): String = listOf(
        "scope=" + (if (scope is AuditScope.All) "ALL" else "SELF"),
        "actor=" + (if (filter.actorId != null) "set" else "none"),
        "target=" + (if (filter.targetActorId != null) "set" else "none"),
        "action=" + (filter.action ?: "none"),
        "range=" + (if (filter.from != null || filter.to != null) "set" else "none"),
        "page=" + (if (filter.beforeEventId != null) "next" else "first"),
        "limit=${filter.limit}",
    ).joinToString(";")

    private companion object {
        const val ACTION_AUDIT_VIEWED = "AUDIT_VIEWED"
    }
}

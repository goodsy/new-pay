package com.paycore.admin.account.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AuditViewResult
import com.paycore.admin.account.application.provided.AuditViewUseCase
import com.paycore.admin.account.application.required.AdminSessionReader
import com.paycore.admin.account.domain.AdminAction
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminPermission
import com.paycore.admin.audit.application.provided.AdminAuditQuery
import com.paycore.admin.audit.application.provided.AuditFilter
import com.paycore.admin.audit.application.provided.AuditViewer
import org.springframework.stereotype.Service

/**
 * [AuditViewUseCase] — 감사 조회 범위의 단일 계산 경로(S3 리뷰 m-3): 조회자 계정의 **현재 행**(PK 단건)으로 `AdminPermission`을 판정해 [AuditViewer]를 만든다.
 * 세션·요청 값의 역할·범위는 쓰지 않는다. `AdminAuditQuery`가 `AUDIT_VIEWED`를 먼저 커밋한 뒤 결과를 돌려준다(실패면 `AuditUnavailable` 전파 → 503).
 */
@Service
class AuditViewService(private val reader: AdminSessionReader, private val query: AdminAuditQuery) : AuditViewUseCase {

    override fun search(viewerActorId: String, filter: AuditFilter, context: AdminRequestContext): AuditViewResult {
        val id = runCatching { AdminActorId(viewerActorId) }.getOrNull() ?: return AuditViewResult.Denied
        val viewer = reader.find(id) ?: return AuditViewResult.Denied
        val all = AdminPermission.allows(viewer.status, viewer.activeRoles, AdminAction.VIEW_ALL_AUDIT)
        if (!all && !AdminPermission.allows(viewer.status, viewer.activeRoles, AdminAction.VIEW_OWN_AUDIT)) return AuditViewResult.Denied
        return AuditViewResult.Entries(query.search(AuditViewer(id.value, viewer.displayName, canViewAll = all), filter, context.correlationId, context.remoteIp))
    }
}

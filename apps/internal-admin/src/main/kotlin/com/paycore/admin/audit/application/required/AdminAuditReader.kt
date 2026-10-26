package com.paycore.admin.audit.application.required

import com.paycore.admin.audit.application.provided.AuditEntry
import com.paycore.admin.audit.application.provided.AuditFilter
import com.paycore.admin.audit.domain.AuditScope

/** 감사 조회(jOOQ — CQRS, §14 Reader). [scope]는 필수이며 [AuditScope.Self]는 SQL 조건으로 강제한다(필터의 actorId는 무시). 최신순·keyset 페이지. */
interface AdminAuditReader {
    fun search(scope: AuditScope, filter: AuditFilter): List<AuditEntry>
}

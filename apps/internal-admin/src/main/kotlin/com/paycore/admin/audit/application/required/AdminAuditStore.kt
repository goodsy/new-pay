package com.paycore.admin.audit.application.required

import com.paycore.admin.audit.domain.AdminAuditEvent

/** append-only 감사 기록(§14 Store ① — 결정 로그). 호출자 TX 안에서만(감사 저장 실패 = 행위 실패 — 같은 TX rollback). 수정·삭제 연산 없음. */
interface AdminAuditStore {
    fun append(event: AdminAuditEvent)
}

package com.paycore.admin.audit.application.required

/**
 * 감사 기록 존재 확인(§14 Store ① — 결정 로그 읽기, append-only 표의 쓰기 없음). [AdminAuditTrail][com.paycore.admin.audit.application.provided.AdminAuditTrail] 전용:
 * 이 행위자·action·상관 ID·결과 code의 행이 하나라도 있는가. 색인 `ix_admin_audit_event_actor`(actor_id, event_id)로 행위자 범위만 읽는다.
 */
interface AdminAuditTrailStore {
    fun exists(actorId: String, action: String, correlationId: String, resultCode: String): Boolean
}

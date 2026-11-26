package com.paycore.admin.audit.application

import com.paycore.admin.audit.application.provided.AdminAuditTrail
import com.paycore.admin.audit.application.required.AdminAuditTrailStore
import org.springframework.stereotype.Service

/** [AdminAuditTrail]: 시도 감사(`REQUESTED`) 행 존재 확인 — 읽기만(감사 행을 만들지 않는다, 조회 화면의 `AUDIT_VIEWED`와 다른 내부 확인). */
@Service
class AdminAuditTrailService(private val store: AdminAuditTrailStore) : AdminAuditTrail {
    override fun requested(actorId: String, action: String, correlationId: String): Boolean = store.exists(actorId, action, correlationId, REQUESTED)

    private companion object {
        const val REQUESTED = "REQUESTED"
    }
}

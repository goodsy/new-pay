package com.paycore.admin.audit.application

import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.audit.application.required.AdminAuditStore
import com.paycore.admin.audit.domain.AdminAuditEvent
import com.paycore.admin.audit.domain.AuditActorKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [AdminAuditRecorder]: 호출자 TX 안에서(MANDATORY) 감사 1행 append. 저장 실패는 그대로 전파 — 호출자 TX 전체 rollback(감사 실패 = 행위 실패). */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class AdminAuditRecorderService(private val store: AdminAuditStore) : AdminAuditRecorder {

    override fun record(record: AuditRecord) {
        store.append(
            AdminAuditEvent(
                occurredAt = record.occurredAt,
                actorKind = AuditActorKind.valueOf(record.actorKind),
                actorId = record.actorId,
                actorDisplayName = record.actorDisplayName,
                executorActorId = record.executorActorId,
                action = record.action,
                targetActorId = record.targetActorId,
                resultCode = record.resultCode,
                approvalId = record.approvalId,
                correlationId = record.correlationId,
                remoteIp = record.remoteIp,
                loginNameHmac = record.loginNameHmac,
                detail = record.detail,
                reason = record.reason,
                targetRef = record.targetRef,
            ),
        )
    }
}

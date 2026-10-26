package com.paycore.admin.audit.adapter.persistence

import com.paycore.admin.audit.application.required.AdminAuditStore
import com.paycore.admin.audit.domain.AdminAuditEvent
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** [AdminAuditStore]: persist + flush(제약 위반이 호출자 TX의 이 지점에서 드러난다 — 감사 실패 = 행위 실패). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAdminAuditStore(private val entityManager: EntityManager) : AdminAuditStore {

    override fun append(event: AdminAuditEvent) {
        entityManager.persist(
            JpaAdminAuditEventEntity.newRow(
                event.occurredAt, event.actorKind.name, event.actorId, event.actorDisplayName, event.executorActorId, event.action, event.targetActorId,
                event.resultCode, event.approvalId, event.correlationId, event.remoteIp, event.loginNameHmac, event.detail, event.reason,
            ),
        )
        entityManager.flush()
    }
}

package com.paycore.admin.audit.adapter.persistence

import com.paycore.admin.audit.application.required.AdminAuditTrailStore
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [AdminAuditTrailStore]: JPQL 존재 확인(읽기 전용 짧은 TX — 호출자는 DB TX 밖, MASTER 호출 전). 영속성 컨텍스트의 Entity를 재사용하지 않고 DB를 읽는다(JPQL).
 * runtime 권한은 기존 `admin_audit_event` SELECT·INSERT 그대로(UPDATE·DELETE 없음).
 */
@Component
class JpaAdminAuditTrailStore(private val entityManager: EntityManager) : AdminAuditTrailStore {

    @Transactional(readOnly = true)
    override fun exists(actorId: String, action: String, correlationId: String, resultCode: String): Boolean =
        entityManager.createQuery(
            "SELECT COUNT(e) FROM JpaAdminAuditEventEntity e WHERE e.actorId = :actorId AND e.action = :action AND e.correlationId = :correlationId AND e.resultCode = :resultCode",
            Long::class.javaObjectType,
        )
            .setParameter("actorId", actorId).setParameter("action", action).setParameter("correlationId", correlationId).setParameter("resultCode", resultCode)
            .singleResult.toLong() > 0
}

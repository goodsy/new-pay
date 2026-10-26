package com.paycore.admin.account.adapter.persistence

import com.paycore.admin.account.adapter.persistence.PersistenceFailures.translate
import com.paycore.admin.account.application.required.AdminRoleEventStore
import com.paycore.admin.account.domain.AdminActorId
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.account.domain.RoleEvent
import com.paycore.admin.account.domain.RoleEventActor
import com.paycore.admin.account.domain.RoleEventType
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(propagation = Propagation.MANDATORY)
class JpaAdminRoleEventStore(private val entityManager: EntityManager) : AdminRoleEventStore {

    override fun append(events: List<RoleEvent>): Unit = translate(ENTITY) {
        events.forEach { e ->
            val (kind, actorId) = when (val actor = e.actor) {
                is RoleEventActor.Admin -> "ADMIN" to actor.actorId.value
                RoleEventActor.SystemBootstrap -> "SYSTEM_BOOTSTRAP" to null
                RoleEventActor.SystemRecovery -> "SYSTEM_RECOVERY" to null
            }
            entityManager.persist(JpaAdminRoleEventEntity.newRow(e.target.value, e.role.name, e.type.name, kind, actorId, e.approvalId, e.cause, e.occurredAt))
        }
        entityManager.flush()
    }

    override fun history(target: AdminActorId): List<RoleEvent> = translate(ENTITY) {
        entityManager.createQuery(
            "select e from JpaAdminRoleEventEntity e where e.targetActorId = :target order by e.eventId", JpaAdminRoleEventEntity::class.java,
        ).setParameter("target", target.value).resultList.map { row ->
            RoleEvent(
                target = AdminActorId(row.targetActorId),
                role = AdminRole.valueOf(row.role),
                type = RoleEventType.valueOf(row.eventType),
                actor = when (row.actorKind) {
                    "ADMIN" -> RoleEventActor.Admin(AdminActorId(requireNotNull(row.actorId) { "admin role event without actor" }))
                    "SYSTEM_BOOTSTRAP" -> RoleEventActor.SystemBootstrap
                    "SYSTEM_RECOVERY" -> RoleEventActor.SystemRecovery
                    else -> error("unknown role event actor kind")
                },
                approvalId = row.approvalId,
                cause = row.causeRef,
                occurredAt = row.occurredAt,
            )
        }
    }

    private companion object {
        const val ENTITY = "admin_role_event"
    }
}

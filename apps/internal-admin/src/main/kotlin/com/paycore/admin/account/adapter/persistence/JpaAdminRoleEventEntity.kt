package com.paycore.admin.account.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** admin_role_event 행(append-only — `@Immutable`: Hibernate가 UPDATE를 내지 않는다, runtime은 SELECT·INSERT만). 새 행은 persist만. */
@Entity
@Immutable
@Table(name = "admin_role_event")
class JpaAdminRoleEventEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "event_id", nullable = false, updatable = false)
    var eventId: Long? = null
        protected set

    @field:Column(name = "target_actor_id", nullable = false, updatable = false, length = 30)
    lateinit var targetActorId: String
        protected set

    @field:Column(name = "role", nullable = false, updatable = false, length = 16)
    lateinit var role: String
        protected set

    @field:Column(name = "event_type", nullable = false, updatable = false, length = 16)
    lateinit var eventType: String
        protected set

    @field:Column(name = "actor_kind", nullable = false, updatable = false, length = 20)
    lateinit var actorKind: String
        protected set

    @field:Column(name = "actor_id", updatable = false, length = 30)
    var actorId: String? = null
        protected set

    @field:Column(name = "approval_id", updatable = false, length = 30)
    var approvalId: String? = null
        protected set

    @field:Column(name = "cause_ref", updatable = false, length = 40)
    var causeRef: String? = null
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is JpaAdminRoleEventEntity && eventId != null && eventId == other.eventId)

    override fun hashCode(): Int = eventId?.hashCode() ?: 0

    override fun toString(): String = "JpaAdminRoleEventEntity(eventId=$eventId, type=$eventType, role=$role)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(target: String, role: String, type: String, actorKind: String, actorId: String?, approvalId: String?, cause: String?, at: Instant) =
            JpaAdminRoleEventEntity().also {
                it.targetActorId = target
                it.role = role
                it.eventType = type
                it.actorKind = actorKind
                it.actorId = actorId
                it.approvalId = approvalId
                it.causeRef = cause
                it.occurredAt = at
            }
    }
}

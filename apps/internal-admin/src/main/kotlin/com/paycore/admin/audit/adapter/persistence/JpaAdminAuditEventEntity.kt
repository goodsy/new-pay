package com.paycore.admin.audit.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** admin_audit_event 행(append-only — `@Immutable` + persist만, runtime SELECT·INSERT만: UPDATE·DELETE 1142). */
@Entity
@Immutable
@Table(name = "admin_audit_event")
class JpaAdminAuditEventEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "event_id", nullable = false, updatable = false)
    var eventId: Long? = null
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    @field:Column(name = "actor_kind", nullable = false, updatable = false, length = 20)
    lateinit var actorKind: String
        protected set

    @field:Column(name = "actor_id", updatable = false, length = 30)
    var actorId: String? = null
        protected set

    @field:Column(name = "actor_display_name", updatable = false, length = 100)
    var actorDisplayName: String? = null
        protected set

    @field:Column(name = "executor_actor_id", updatable = false, length = 30)
    var executorActorId: String? = null
        protected set

    @field:Column(name = "action", nullable = false, updatable = false, length = 48)
    lateinit var action: String
        protected set

    @field:Column(name = "target_actor_id", updatable = false, length = 30)
    var targetActorId: String? = null
        protected set

    @field:Column(name = "result_code", nullable = false, updatable = false, length = 48)
    lateinit var resultCode: String
        protected set

    @field:Column(name = "approval_id", updatable = false, length = 30)
    var approvalId: String? = null
        protected set

    @field:Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
    lateinit var correlationId: String
        protected set

    @field:Column(name = "remote_ip", updatable = false, length = 45)
    var remoteIp: String? = null
        protected set

    @field:Column(name = "login_name_hmac", updatable = false, length = 32, columnDefinition = "binary(32)")
    var loginNameHmac: ByteArray? = null
        protected set

    @field:Column(name = "detail", updatable = false, length = 500)
    var detail: String? = null
        protected set

    @field:Column(name = "reason", updatable = false, length = 500)
    var reason: String? = null
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is JpaAdminAuditEventEntity && eventId != null && eventId == other.eventId)

    override fun hashCode(): Int = eventId?.hashCode() ?: 0

    override fun toString(): String = "JpaAdminAuditEventEntity(eventId=$eventId, action=$action, result=$resultCode)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            occurredAt: Instant, actorKind: String, actorId: String?, actorDisplayName: String?, executorActorId: String?, action: String,
            targetActorId: String?, resultCode: String, approvalId: String?, correlationId: String, remoteIp: String?, loginNameHmac: ByteArray?, detail: String?, reason: String? = null,
        ) = JpaAdminAuditEventEntity().also {
            it.occurredAt = occurredAt
            it.actorKind = actorKind
            it.actorId = actorId
            it.actorDisplayName = actorDisplayName
            it.executorActorId = executorActorId
            it.action = action
            it.targetActorId = targetActorId
            it.resultCode = resultCode
            it.approvalId = approvalId
            it.correlationId = correlationId
            it.remoteIp = remoteIp
            it.loginNameHmac = loginNameHmac
            it.detail = detail
            it.reason = reason
        }
    }
}

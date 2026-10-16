package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant

/** (activation_id, seq) 복합 키. */
@Embeddable
class CardRouteEventKey protected constructor() : Serializable {
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is CardRouteEventKey && activationId == other.activationId && seq == other.seq)

    override fun hashCode(): Int = 31 * activationId.hashCode() + seq

    override fun toString(): String = "CardRouteEventKey($activationId#$seq)"

    companion object {
        fun of(activationId: String, seq: Int) = CardRouteEventKey().also {
            it.activationId = activationId
            it.seq = seq
        }
    }
}

/** card_route_activation_event 행(append-only 전이 이력). */
@Entity
@Immutable
@Table(name = "card_route_activation_event")
class JpaCardRouteEventEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: CardRouteEventKey
        protected set

    @field:Column(name = "from_status", updatable = false, length = 16)
    var fromStatus: String? = null
        protected set

    @field:Column(name = "to_status", nullable = false, updatable = false, length = 16)
    lateinit var toStatus: String
        protected set

    @field:Column(name = "evidence_ref", updatable = false, length = 128)
    var evidenceRef: String? = null
        protected set

    @field:Column(name = "reason", updatable = false, length = 200)
    var reason: String? = null
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaCardRouteEventEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaCardRouteEventEntity(key=$key, to=$toStatus)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            key: CardRouteEventKey,
            from: String?,
            to: String,
            evidenceRef: String?,
            reason: String?,
            actor: String,
            requestRecordId: String,
            at: Instant,
        ) = JpaCardRouteEventEntity().also {
            it.key = key
            it.fromStatus = from
            it.toStatus = to
            it.evidenceRef = evidenceRef
            it.reason = reason
            it.actorRef = actor
            it.requestRecordId = requestRecordId
            it.occurredAt = at
        }
    }
}

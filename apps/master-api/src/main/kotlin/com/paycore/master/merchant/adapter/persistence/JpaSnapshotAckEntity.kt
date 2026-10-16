package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** entitlement_snapshot_ack 행(append-only ACK). */
@Entity
@Table(name = "entitlement_snapshot_ack")
class JpaSnapshotAckEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "outbox_event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var outboxEventId: String
        protected set

    @field:Column(name = "consumer", nullable = false, updatable = false, length = 64)
    lateinit var consumer: String
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 24)
    lateinit var outcome: String
        protected set

    @field:Column(name = "applied_revision", nullable = false, updatable = false)
    var appliedRevision: Long = 0
        protected set

    @field:Column(name = "acked_at", nullable = false, updatable = false)
    lateinit var ackedAt: Instant
        protected set

    @field:Column(name = "original_outcome", updatable = false, length = 24)
    var originalOutcome: String? = null
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaSnapshotAckEntity(id=$id, outcome=$outcome)"

    companion object {
        fun newRow(eventId: String, consumer: String, outcome: String, appliedRevision: Long, at: Instant, originalOutcome: String?) = JpaSnapshotAckEntity().also {
            it.originalOutcome = originalOutcome
            it.outboxEventId = eventId
            it.consumer = consumer
            it.outcome = outcome
            it.appliedRevision = appliedRevision
            it.ackedAt = at
        }
    }
}

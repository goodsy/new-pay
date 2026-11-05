package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** financial_fact_relay_ack 행(append-only ACK 감사 — `@Immutable`, persist만, runtime 권한 SELECT·INSERT). */
@Entity
@Immutable
@Table(name = "financial_fact_relay_ack")
class JpaFinancialFactRelayAckEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "outbox_id", nullable = false, updatable = false)
    var outboxId: Long = 0
        protected set

    @field:Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var eventId: String
        protected set

    @field:Column(name = "consumer", nullable = false, updatable = false, length = 64)
    lateinit var consumer: String
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 16)
    lateinit var outcome: String
        protected set

    @field:Column(name = "original_outcome", updatable = false, length = 16)
    var originalOutcome: String? = null
        protected set

    @field:Column(name = "claim_owner", nullable = false, updatable = false, length = 64)
    lateinit var claimOwner: String
        protected set

    @field:Column(name = "acked_at", nullable = false, updatable = false)
    lateinit var ackedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaFinancialFactRelayAckEntity(id=$id, outboxId=$outboxId, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(outboxId: Long, eventId: String, consumer: String, outcome: String, originalOutcome: String?, claimOwner: String, ackedAt: Instant) =
            JpaFinancialFactRelayAckEntity().also {
                it.outboxId = outboxId
                it.eventId = eventId
                it.consumer = consumer
                it.outcome = outcome
                it.originalOutcome = originalOutcome
                it.claimOwner = claimOwner
                it.ackedAt = ackedAt
            }
    }
}

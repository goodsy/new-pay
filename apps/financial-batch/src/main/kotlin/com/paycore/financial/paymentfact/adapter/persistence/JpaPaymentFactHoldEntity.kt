package com.paycore.financial.paymentfact.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/** payment_fact_hold 행(append-only 보류 — 해제 경로 없음, 해제 규칙은 ACQUIRING-001). */
@Entity
@Immutable
@Table(name = "payment_fact_hold")
class JpaPaymentFactHoldEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "fact_kind", nullable = false, updatable = false, length = 16)
    lateinit var factKind: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 40)
    lateinit var reason: String
        protected set

    @field:Column(name = "source_event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var sourceEventId: String
        protected set

    @field:Column(name = "opened_at", nullable = false, updatable = false)
    lateinit var openedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaPaymentFactHoldEntity(id=$id, transactionId=$transactionId, reason=$reason)"

    companion object {
        fun newRow(transactionId: String, factKind: String, paymentId: String, reason: String, sourceEventId: String, openedAt: Instant) =
            JpaPaymentFactHoldEntity().also {
                it.transactionId = transactionId
                it.factKind = factKind
                it.paymentId = paymentId
                it.reason = reason
                it.sourceEventId = sourceEventId
                it.openedAt = openedAt
            }
    }
}

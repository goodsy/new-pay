package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/** incident_hold 행(append-only, INSERT만). 유일성 UNIQUE(OrderKey, payment_id, reason). */
@Entity
@Table(name = "incident_hold")
class JpaIncidentHoldEntity protected constructor() {
    /** DB가 부여하는 순서 키(auto increment). 저장 전에는 null이라 equals/hashCode에 쓰지 않는다. */
    @field:Id
    @field:jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 40)
    lateinit var reason: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    /** 자연 키(OrderKey, paymentId, reason) — 저장 전후로 같은 값이라 Set에 넣어도 안정적이다. */
    private fun naturalKey() = listOf(environment, merchantAccountId, merchantOrderId, paymentId, reason)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return naturalKey() == (other as JpaIncidentHoldEntity).naturalKey()
    }

    override fun hashCode(): Int = naturalKey().hashCode()

    override fun toString(): String = "JpaIncidentHoldEntity(id=$id, paymentId=$paymentId, reason=$reason)"

    companion object {
        fun newRow(order: OrderKeyColumns, paymentId: String, reason: String, createdAt: Instant): JpaIncidentHoldEntity =
            JpaIncidentHoldEntity().also {
                it.environment = order.environment
                it.merchantAccountId = order.merchantAccountId
                it.merchantOrderId = order.merchantOrderId
                it.paymentId = paymentId
                it.reason = reason
                it.createdAt = createdAt
            }
    }
}

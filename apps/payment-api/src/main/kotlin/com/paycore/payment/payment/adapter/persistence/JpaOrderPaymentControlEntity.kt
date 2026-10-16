package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** order_payment_control 행. 최초 INSERT는 adapter의 native INSERT … ON DUPLICATE KEY(경쟁 직렬화)이며 JPA는 읽기·갱신만 한다. */
@Entity
@Table(name = "order_payment_control")
class JpaOrderPaymentControlEntity protected constructor() {
    @field:EmbeddedId
    lateinit var orderKey: OrderKeyColumns
        protected set

    @field:Column(name = "fixed_intent_fingerprint", nullable = false, updatable = false, length = 80)
    lateinit var fixedIntentFingerprint: String
        protected set

    @field:Column(name = "current_payment_id", nullable = false, length = 64)
    lateinit var currentPaymentId: String

    @field:Column(name = "generation", nullable = false)
    var generation: Long = 0

    @field:Column(name = "authorized_payment_id", length = 64)
    var authorizedPaymentId: String? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return orderKey == (other as JpaOrderPaymentControlEntity).orderKey
    }

    override fun hashCode(): Int = orderKey.hashCode()

    override fun toString(): String = "JpaOrderPaymentControlEntity($orderKey, generation=$generation, version=$version)"
}

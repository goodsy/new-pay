package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/** authorization_recovery_schedule 행(AU-16 bookkeeping). 식별·주문 키 열은 바뀌지 않고, 일정 열만 갱신한다(`@Version`). */
@Entity
@Table(name = "authorization_recovery_schedule")
class JpaRecoveryScheduleEntity protected constructor() {
    @field:Id
    @field:Column(name = "attempt_id", nullable = false, updatable = false, length = 64)
    lateinit var attemptId: String
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
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

    @field:Column(name = "dispatch_lease_until")
    var dispatchLeaseUntil: Instant? = null

    @field:Column(name = "next_check_at", nullable = false)
    lateinit var nextCheckAt: Instant

    @field:Column(name = "inquiry_count", nullable = false)
    var inquiryCount: Int = 0

    @field:Column(name = "escalated_at")
    var escalatedAt: Instant? = null

    @field:Column(name = "closed_at")
    var closedAt: Instant? = null

    @field:Column(name = "recovery_config_version", nullable = false, updatable = false, length = 32)
    lateinit var recoveryConfigVersion: String
        protected set

    @field:Column(name = "escalation_config_version", length = 32)
    var escalationConfigVersion: String? = null

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
        return attemptId == (other as JpaRecoveryScheduleEntity).attemptId
    }

    override fun hashCode(): Int = attemptId.hashCode()

    override fun toString(): String = "JpaRecoveryScheduleEntity(attemptId=$attemptId, closedAt=$closedAt)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            attemptId: String, authorizationId: String, paymentId: String, environment: String, merchantAccountId: String, merchantOrderId: String,
            nextCheckAt: Instant, recoveryConfigVersion: String, at: Instant,
        ) = JpaRecoveryScheduleEntity().also {
            it.recoveryConfigVersion = recoveryConfigVersion
            it.attemptId = attemptId
            it.authorizationId = authorizationId
            it.paymentId = paymentId
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
            it.nextCheckAt = nextCheckAt
            it.inquiryCount = 0
            it.createdAt = at
            it.updatedAt = at
        }
    }
}

package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant
import java.time.LocalDate

/**
 * limit_reservation 행(승인 operation 하나의 일 한도 점유 — D-AUTH-007·010). 범위·금액·순번은 고정, 상태·초과 표시만 바뀐다. 동시 변경 직렬화는 부모
 * daily_limit_usage 행 잠금이 하므로 이 행에는 version이 없다(부모 version을 함께 올린다).
 */
@Entity
@Table(name = "limit_reservation")
class JpaLimitReservationEntity protected constructor() {
    @field:Id
    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, length = 3)
    lateinit var currency: String
        protected set

    @field:Column(name = "business_date", nullable = false, updatable = false)
    lateinit var businessDate: LocalDate
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "state", nullable = false, length = 12)
    lateinit var state: String

    @field:Column(name = "excess", length = 16)
    var excess: String? = null

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return authorizationId == (other as JpaLimitReservationEntity).authorizationId
    }

    override fun hashCode(): Int = authorizationId.hashCode()

    override fun toString(): String = "JpaLimitReservationEntity(authorizationId=$authorizationId, state=$state)"

    companion object {
        fun newRow(key: LimitUsageKeyColumns, authorizationId: String, amountMinor: Long, state: String, excess: String?, seq: Int, at: Instant): JpaLimitReservationEntity =
            JpaLimitReservationEntity().also {
                it.authorizationId = authorizationId
                it.environment = key.environment
                it.merchantAccountId = key.merchantAccountId
                it.currency = key.currency
                it.businessDate = key.businessDate
                it.amountMinor = amountMinor
                it.state = state
                it.excess = excess
                it.seq = seq
                it.updatedAt = at
            }
    }
}

package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

/** daily_limit_usage 복합 PK = (환경, MID, 통화, Asia/Seoul 업무일) — domain `LimitUsageKey`와 같다. */
@Embeddable
class LimitUsageKeyColumns protected constructor() : Serializable {
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

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is LimitUsageKeyColumns && environment == other.environment && merchantAccountId == other.merchantAccountId &&
                currency == other.currency && businessDate == other.businessDate
            )

    override fun hashCode(): Int = listOf(environment, merchantAccountId, currency, businessDate).hashCode()

    override fun toString(): String = "LimitUsageKeyColumns($environment/$merchantAccountId/$currency/$businessDate)"

    companion object {
        private const val serialVersionUID = 1L

        fun of(environment: String, merchantAccountId: String, currency: String, businessDate: LocalDate): LimitUsageKeyColumns = LimitUsageKeyColumns().also {
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.currency = currency
            it.businessDate = businessDate
        }
    }
}

/**
 * daily_limit_usage 행(AUTH-001 명세 §7.1). 최초 INSERT는 adapter의 native INSERT … ON DUPLICATE KEY(경쟁 직렬화), JPA는 잠금 읽기·version 갱신만 한다.
 * 점유 행(limit_reservation)만 바뀌어도 이 행의 version을 올린다(`PESSIMISTIC_FORCE_INCREMENT`) — 같은 사용량을 동시에 바꾸려는 다른 TX가 version으로도 충돌한다.
 */
@Entity
@Table(name = "daily_limit_usage")
class JpaDailyLimitUsageEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: LimitUsageKeyColumns
        protected set

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
        return key == (other as JpaDailyLimitUsageEntity).key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaDailyLimitUsageEntity($key, version=$version)"
}

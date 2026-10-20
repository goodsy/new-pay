package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * return_balance 행(CANCEL-001 명세 §8.1, CC-02 — Payment 1:1). 원 승인 Money·원 거래는 고정, 합계 열(completed·reserved·forced)과 업무 [revision]은 예약 행에서 계산해 쓴다(CN-17 —
 * DB CHECK `completed + reserved ≤ authorized`의 대상). [revision](운영자 승인 대상 버전)과 [version](JPA 낙관적 잠금)은 별개다(CN-16).
 */
@Entity
@Table(name = "return_balance")
class JpaReturnBalanceEntity protected constructor() {
    @field:Id
    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "original_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var originalTransactionId: String
        protected set

    @field:Column(name = "authorized_minor", nullable = false, updatable = false)
    var authorizedMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, length = 3)
    lateinit var currency: String
        protected set

    @field:Column(name = "completed_minor", nullable = false)
    var completedMinor: Long = 0

    @field:Column(name = "reserved_minor", nullable = false)
    var reservedMinor: Long = 0

    @field:Column(name = "forced_minor", nullable = false)
    var forcedMinor: Long = 0

    @field:Column(name = "revision", nullable = false)
    var revision: Long = 0

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
        return paymentId == (other as JpaReturnBalanceEntity).paymentId
    }

    override fun hashCode(): Int = paymentId.hashCode()

    override fun toString(): String = "JpaReturnBalanceEntity(paymentId=$paymentId, revision=$revision, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            paymentId: String, authorizationId: String, originalTransactionId: String, authorizedMinor: Long, currency: String, at: Instant,
        ): JpaReturnBalanceEntity = JpaReturnBalanceEntity().also {
            it.paymentId = paymentId
            it.authorizationId = authorizationId
            it.originalTransactionId = originalTransactionId
            it.authorizedMinor = authorizedMinor
            it.currency = currency
            it.createdAt = at
            it.updatedAt = at
        }
    }
}

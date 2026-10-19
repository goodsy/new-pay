package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/** incident_hold 행(append-only, INSERT만). 유일성 UNIQUE(OrderKey, payment_id, reason, occurrence)(AUTH-001 V11 — D-AUTH-012·013), 여는 관찰 키(H-1, NULL = legacy·취소 사건). */
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

    @field:Column(name = "occurrence", nullable = false, updatable = false)
    var occurrence: Int = 1
        protected set

    @field:Column(name = "source_attempt_id", updatable = false, length = 64)
    var sourceAttemptId: String? = null
        protected set

    @field:Column(name = "source_kind", updatable = false, length = 16)
    var sourceKind: String? = null
        protected set

    @field:Column(name = "source_result_id", updatable = false, length = 128)
    var sourceResultId: String? = null
        protected set

    /** 여는 관찰의 variant(검토 #1 H-1 — 0 = 그 키의 첫 관찰, n ≥ 1 = 상충 증거). 출처 열 넷은 함께 NULL이거나 함께 값이 있다. */
    @field:Column(name = "source_variant", updatable = false)
    var sourceVariant: Int? = null
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    /** 자연 키(OrderKey, paymentId, reason, occurrence) — 저장 전후로 같은 값이라 Set에 넣어도 안정적이다. */
    private fun naturalKey() = listOf(environment, merchantAccountId, merchantOrderId, paymentId, reason, occurrence)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return naturalKey() == (other as JpaIncidentHoldEntity).naturalKey()
    }

    override fun hashCode(): Int = naturalKey().hashCode()

    override fun toString(): String = "JpaIncidentHoldEntity(id=$id, paymentId=$paymentId, reason=$reason, occurrence=$occurrence)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            order: OrderKeyColumns, paymentId: String, reason: String, occurrence: Int, sourceAttemptId: String?, sourceKind: String?, sourceResultId: String?,
            sourceVariant: Int?, createdAt: Instant,
        ): JpaIncidentHoldEntity =
            JpaIncidentHoldEntity().also {
                it.environment = order.environment
                it.merchantAccountId = order.merchantAccountId
                it.merchantOrderId = order.merchantOrderId
                it.paymentId = paymentId
                it.reason = reason
                it.occurrence = occurrence
                it.sourceAttemptId = sourceAttemptId
                it.sourceKind = sourceKind
                it.sourceResultId = sourceResultId
                it.sourceVariant = sourceVariant
                it.createdAt = createdAt
            }
    }
}

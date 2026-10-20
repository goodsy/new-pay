package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * return_reservation 행(CANCEL-001 명세 §8.1). 취소 하나·금액·잔액 안 순서([seq])는 고정, 상태·초과 표시·완료 순번만 바뀐다. 행은 지우지 않는다.
 * 생성 열 `in_flight_payment_id`(Payment당 RESERVED 하나)는 매핑하지 않는다.
 */
@Entity
@Table(name = "return_reservation")
class JpaReturnReservationEntity protected constructor() {
    @field:Id
    @field:Column(name = "cancellation_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "state", nullable = false, length = 16)
    lateinit var state: String

    @field:Column(name = "excess", length = 8)
    var excess: String? = null

    @field:Column(name = "completion_sequence")
    var completionSequence: Int? = null

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return cancellationId == (other as JpaReturnReservationEntity).cancellationId
    }

    override fun hashCode(): Int = cancellationId.hashCode()

    override fun toString(): String = "JpaReturnReservationEntity(cancellationId=$cancellationId, state=$state)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            cancellationId: String, paymentId: String, seq: Int, amountMinor: Long, state: String, excess: String?, completionSequence: Int?, at: Instant,
        ): JpaReturnReservationEntity = JpaReturnReservationEntity().also {
            it.cancellationId = cancellationId
            it.paymentId = paymentId
            it.seq = seq
            it.amountMinor = amountMinor
            it.state = state
            it.excess = excess
            it.completionSequence = completionSequence
            it.updatedAt = at
        }
    }
}

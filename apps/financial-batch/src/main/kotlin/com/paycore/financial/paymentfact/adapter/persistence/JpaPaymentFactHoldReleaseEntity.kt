package com.paycore.financial.paymentfact.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * payment_fact_hold_release 행(D-FFR-003 append-only 해제 기록 — ACQUIRING-001 §12). `@Immutable` + 모든 열 updatable=false: Hibernate가 UPDATE를 만들지 않고
 * DB runtime 권한도 SELECT·INSERT뿐이다(V6). 보류 원본 행과 연관(`@ManyToOne`)을 두지 않는다 — hold_id 값만 둬서 로딩·cascade 경로가 없다.
 */
@Entity
@Immutable
@Table(name = "payment_fact_hold_release")
class JpaPaymentFactHoldReleaseEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "hold_id", nullable = false, updatable = false)
    var holdId: Long = 0
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "cancellation_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationTransactionId: String
        protected set

    @field:Column(name = "authorization_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationTransactionId: String
        protected set

    @field:Column(name = "decision", nullable = false, updatable = false, length = 16)
    lateinit var decision: String
        protected set

    @field:Column(name = "mismatch_reasons", updatable = false, length = 160)
    var mismatchReasons: String? = null
        protected set

    @field:Column(name = "decided_by", nullable = false, updatable = false, length = 8)
    lateinit var decidedBy: String
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaPaymentFactHoldReleaseEntity(id=$id, holdId=$holdId, decision=$decision)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            holdId: Long,
            paymentId: String,
            cancellationTransactionId: String,
            authorizationTransactionId: String,
            decision: String,
            mismatchReasons: String?,
            decidedBy: String,
            decidedAt: Instant,
        ) = JpaPaymentFactHoldReleaseEntity().also {
            it.holdId = holdId
            it.paymentId = paymentId
            it.cancellationTransactionId = cancellationTransactionId
            it.authorizationTransactionId = authorizationTransactionId
            it.decision = decision
            it.mismatchReasons = mismatchReasons
            it.decidedBy = decidedBy
            it.decidedAt = decidedAt
        }
    }
}

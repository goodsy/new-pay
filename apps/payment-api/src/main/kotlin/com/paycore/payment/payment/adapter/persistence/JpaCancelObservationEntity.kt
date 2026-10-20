package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * cancel_observation 행(append-only 기관 취소 결과 — CANCEL-001 명세 §8.1). `@Immutable` + runtime 권한 SELECT·INSERT(1142) 두 겹. [variant] 0 = 같은 키의 첫 관찰,
 * n ≥ 1 = 같은 키·다른 내용의 상충 증거. 확정 취소 조건은 APPROVED에만. 원문은 hash만.
 */
@Entity
@Immutable
@Table(name = "cancel_observation")
class JpaCancelObservationEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "cancellation_id", nullable = false, updatable = false, length = 64)
    lateinit var cancellationId: String
        protected set

    @field:Column(name = "attempt_id", nullable = false, updatable = false, length = 64)
    lateinit var attemptId: String
        protected set

    @field:Column(name = "source", nullable = false, updatable = false, length = 16)
    lateinit var source: String
        protected set

    @field:Column(name = "source_result_id", nullable = false, updatable = false, length = 128)
    lateinit var sourceResultId: String
        protected set

    @field:Column(name = "variant", nullable = false, updatable = false)
    var variant: Int = 0
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 24)
    lateinit var outcome: String
        protected set

    @field:Column(name = "cancelled_amount_minor", updatable = false)
    var cancelledAmountMinor: Long? = null
        protected set

    @field:Column(name = "cancelled_currency", updatable = false, length = 3)
    var cancelledCurrency: String? = null
        protected set

    @field:Column(name = "original_provider_transaction_id", updatable = false, length = 64)
    var originalProviderTransactionId: String? = null
        protected set

    @field:Column(name = "provider_transaction_id", updatable = false, length = 64)
    var providerTransactionId: String? = null
        protected set

    @field:Column(name = "cancel_approval_number", updatable = false, length = 32)
    var cancelApprovalNumber: String? = null
        protected set

    @field:Column(name = "institution_transaction_date", updatable = false)
    var institutionTransactionDate: LocalDate? = null
        protected set

    @field:Column(name = "institution_business_date", updatable = false)
    var institutionBusinessDate: LocalDate? = null
        protected set

    @field:Column(name = "evidence_hash", updatable = false, length = 80)
    var evidenceHash: String? = null
        protected set

    @field:Column(name = "observed_at", nullable = false, updatable = false)
    lateinit var observedAt: Instant
        protected set

    private fun naturalKey() = listOf(attemptId, source, sourceResultId, variant)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return naturalKey() == (other as JpaCancelObservationEntity).naturalKey()
    }

    override fun hashCode(): Int = naturalKey().hashCode()

    override fun toString(): String = "JpaCancelObservationEntity(attemptId=$attemptId, source=$source, variant=$variant, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            cancellationId: String, attemptId: String, source: String, sourceResultId: String, variant: Int, outcome: String, cancelledAmountMinor: Long?,
            cancelledCurrency: String?, originalProviderTransactionId: String?, providerTransactionId: String?, cancelApprovalNumber: String?,
            institutionTransactionDate: LocalDate?, institutionBusinessDate: LocalDate?, evidenceHash: String?, observedAt: Instant,
        ): JpaCancelObservationEntity = JpaCancelObservationEntity().also {
            it.cancellationId = cancellationId
            it.attemptId = attemptId
            it.source = source
            it.sourceResultId = sourceResultId
            it.variant = variant
            it.outcome = outcome
            it.cancelledAmountMinor = cancelledAmountMinor
            it.cancelledCurrency = cancelledCurrency
            it.originalProviderTransactionId = originalProviderTransactionId
            it.providerTransactionId = providerTransactionId
            it.cancelApprovalNumber = cancelApprovalNumber
            it.institutionTransactionDate = institutionTransactionDate
            it.institutionBusinessDate = institutionBusinessDate
            it.evidenceHash = evidenceHash
            it.observedAt = observedAt
        }
    }
}

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
 * institution_observation 행(append-only 기관 결과 — AUTH-001 명세 §7.1). `@Immutable`: 변경 감지가 UPDATE를 만들지 않고 runtime 권한도 SELECT·INSERT뿐(1142) — 두 겹.
 * [variant] 0 = 같은 관찰 키의 첫 관찰, n ≥ 1 = 같은 키·다른 내용의 상충 증거(review m-1). 원문은 hash만.
 */
@Entity
@Immutable
@Table(name = "institution_observation")
class JpaInstitutionObservationEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
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

    @field:Column(name = "approved_amount_minor", updatable = false)
    var approvedAmountMinor: Long? = null
        protected set

    @field:Column(name = "approved_currency", updatable = false, length = 3)
    var approvedCurrency: String? = null
        protected set

    @field:Column(name = "approved_plan_kind", updatable = false, length = 12)
    var approvedPlanKind: String? = null
        protected set

    @field:Column(name = "approved_installment_months", updatable = false)
    var approvedInstallmentMonths: Int? = null
        protected set

    @field:Column(name = "approved_card_type", updatable = false, length = 8)
    var approvedCardType: String? = null
        protected set

    @field:Column(name = "approval_number", updatable = false, length = 32)
    var approvalNumber: String? = null
        protected set

    @field:Column(name = "provider_transaction_id", updatable = false, length = 64)
    var providerTransactionId: String? = null
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

    /** 자연 키(관찰 키 + variant) — 저장 전후 같은 값. */
    private fun naturalKey() = listOf(attemptId, source, sourceResultId, variant)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return naturalKey() == (other as JpaInstitutionObservationEntity).naturalKey()
    }

    override fun hashCode(): Int = naturalKey().hashCode()

    override fun toString(): String = "JpaInstitutionObservationEntity(attemptId=$attemptId, source=$source, variant=$variant, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            authorizationId: String, attemptId: String, source: String, sourceResultId: String, variant: Int, outcome: String,
            approvedAmountMinor: Long?, approvedCurrency: String?, approvedPlanKind: String?, approvedInstallmentMonths: Int?, approvedCardType: String?,
            approvalNumber: String?, providerTransactionId: String?, institutionTransactionDate: LocalDate?, institutionBusinessDate: LocalDate?, evidenceHash: String?,
            observedAt: Instant,
        ): JpaInstitutionObservationEntity = JpaInstitutionObservationEntity().also {
            it.authorizationId = authorizationId
            it.attemptId = attemptId
            it.source = source
            it.sourceResultId = sourceResultId
            it.variant = variant
            it.outcome = outcome
            it.approvedAmountMinor = approvedAmountMinor
            it.approvedCurrency = approvedCurrency
            it.approvedPlanKind = approvedPlanKind
            it.approvedInstallmentMonths = approvedInstallmentMonths
            it.approvedCardType = approvedCardType
            it.approvalNumber = approvalNumber
            it.providerTransactionId = providerTransactionId
            it.institutionTransactionDate = institutionTransactionDate
            it.institutionBusinessDate = institutionBusinessDate
            it.evidenceHash = evidenceHash
            it.observedAt = observedAt
        }
    }
}

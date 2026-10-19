package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * payment_transaction 행(append-only 확정 금융 사실 — D-AUTH-003, AUTH-001 명세 §7.1). `@Immutable` + runtime 권한 SELECT·INSERT(이중). 지금은 승인 거래만(취소 열은 CANCEL-001).
 * 생성 열 `authorization_txn_key`는 매핑하지 않는다(Authorization당 승인 거래 하나를 DB가 지킨다).
 */
@Entity
@Immutable
@Table(name = "payment_transaction")
class JpaPaymentTransactionEntity protected constructor() {
    @field:Id
    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "type", nullable = false, updatable = false, length = 16)
    lateinit var type: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "authorization_id", nullable = false, updatable = false, length = 64)
    lateinit var authorizationId: String
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, length = 3)
    lateinit var currency: String
        protected set

    @field:Column(name = "plan_kind", nullable = false, updatable = false, length = 12)
    lateinit var planKind: String
        protected set

    @field:Column(name = "installment_months", updatable = false)
    var installmentMonths: Int? = null
        protected set

    @field:Column(name = "card_type", nullable = false, updatable = false, length = 8)
    lateinit var cardType: String
        protected set

    @field:Column(name = "approval_number", nullable = false, updatable = false, length = 32)
    lateinit var approvalNumber: String
        protected set

    @field:Column(name = "provider_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var providerTransactionId: String
        protected set

    @field:Column(name = "institution_transaction_date", nullable = false, updatable = false)
    lateinit var institutionTransactionDate: LocalDate
        protected set

    /** 기관 영업일(검토 #1 m-2, nullable — 의미는 Q-CALENDAR). */
    @field:Column(name = "institution_business_date", updatable = false)
    var institutionBusinessDate: LocalDate? = null
        protected set

    @field:Column(name = "source_attempt_id", nullable = false, updatable = false, length = 64)
    lateinit var sourceAttemptId: String
        protected set

    @field:Column(name = "source_kind", nullable = false, updatable = false, length = 16)
    lateinit var sourceKind: String
        protected set

    @field:Column(name = "source_result_id", nullable = false, updatable = false, length = 128)
    lateinit var sourceResultId: String
        protected set

    @field:Column(name = "recorded_by", nullable = false, updatable = false, length = 16)
    lateinit var recordedBy: String
        protected set

    @field:Column(name = "confirmed_at", nullable = false, updatable = false)
    lateinit var confirmedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return transactionId == (other as JpaPaymentTransactionEntity).transactionId
    }

    override fun hashCode(): Int = transactionId.hashCode()

    override fun toString(): String = "JpaPaymentTransactionEntity(transactionId=$transactionId, type=$type, authorizationId=$authorizationId)"

    companion object {
        @Suppress("LongParameterList")
        fun newAuthorizationRow(
            transactionId: String, paymentId: String, authorizationId: String, amountMinor: Long, currency: String, planKind: String, installmentMonths: Int?,
            cardType: String, approvalNumber: String, providerTransactionId: String, institutionTransactionDate: LocalDate, institutionBusinessDate: LocalDate?, sourceAttemptId: String,
            sourceKind: String, sourceResultId: String, recordedBy: String, confirmedAt: Instant,
        ): JpaPaymentTransactionEntity = JpaPaymentTransactionEntity().also {
            it.transactionId = transactionId
            it.type = "AUTHORIZATION"
            it.paymentId = paymentId
            it.authorizationId = authorizationId
            it.amountMinor = amountMinor
            it.currency = currency
            it.planKind = planKind
            it.installmentMonths = installmentMonths
            it.cardType = cardType
            it.approvalNumber = approvalNumber
            it.providerTransactionId = providerTransactionId
            it.institutionTransactionDate = institutionTransactionDate
            it.institutionBusinessDate = institutionBusinessDate
            it.sourceAttemptId = sourceAttemptId
            it.sourceKind = sourceKind
            it.sourceResultId = sourceResultId
            it.recordedBy = recordedBy
            it.confirmedAt = confirmedAt
        }
    }
}

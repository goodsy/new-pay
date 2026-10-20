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
 * payment_transaction 행(append-only 확정 금융 사실 — D-AUTH-003, AUTH-001 명세 §7.1, CANCEL-001 V17). `@Immutable` + runtime 권한 SELECT·INSERT(이중).
 * 승인 거래(AUTHORIZATION)와 취소 거래(CANCEL)가 한 표다: 승인 전용 열(Plan·cardType·승인번호)은 CANCEL에서 NULL, 취소 열은 AUTHORIZATION에서 NULL(DB CHECK가 type별 짝을 강제 — rev 0.3 m-2).
 * 생성 열 `authorization_txn_key`·`cancel_txn_key`는 매핑하지 않는다(Authorization당 승인 거래 하나·취소당 CANCEL 거래 하나를 DB가 지킨다).
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

    /** 승인 거래만(CANCEL은 NULL). */
    @field:Column(name = "plan_kind", updatable = false, length = 12)
    var planKind: String? = null
        protected set

    @field:Column(name = "installment_months", updatable = false)
    var installmentMonths: Int? = null
        protected set

    /** 승인 거래만(CANCEL은 NULL). */
    @field:Column(name = "card_type", updatable = false, length = 8)
    var cardType: String? = null
        protected set

    /** 승인번호(승인 필수) 또는 취소 승인번호(기관이 주지 않을 수 있어 NULL 허용). */
    @field:Column(name = "approval_number", updatable = false, length = 32)
    var approvalNumber: String? = null
        protected set

    /** 취소 거래만: 취소 operation·원 승인 거래·범위·원 승인 기관 거래 식별자. */
    @field:Column(name = "cancellation_id", updatable = false, length = 64)
    var cancellationId: String? = null
        protected set

    @field:Column(name = "original_transaction_id", updatable = false, length = 64)
    var originalTransactionId: String? = null
        protected set

    @field:Column(name = "cancel_scope", updatable = false, length = 8)
    var cancelScope: String? = null
        protected set

    @field:Column(name = "original_provider_transaction_id", updatable = false, length = 64)
    var originalProviderTransactionId: String? = null
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
        /** 취소 거래 행(CANCEL-001 V17). recordedBy는 결과 반영(RESOLUTION) — RECON이 기록하게 되면 RECONCILIATION(rev 0.3 n-9). */
        @Suppress("LongParameterList")
        fun newCancelRow(
            transactionId: String, paymentId: String, authorizationId: String, cancellationId: String, originalTransactionId: String, cancelScope: String,
            amountMinor: Long, currency: String, cancelApprovalNumber: String?, providerTransactionId: String, originalProviderTransactionId: String,
            institutionTransactionDate: LocalDate, institutionBusinessDate: LocalDate?, sourceAttemptId: String, sourceKind: String, sourceResultId: String,
            recordedBy: String, confirmedAt: Instant,
        ): JpaPaymentTransactionEntity = JpaPaymentTransactionEntity().also {
            it.transactionId = transactionId
            it.type = "CANCEL"
            it.paymentId = paymentId
            it.authorizationId = authorizationId
            it.cancellationId = cancellationId
            it.originalTransactionId = originalTransactionId
            it.cancelScope = cancelScope
            it.amountMinor = amountMinor
            it.currency = currency
            it.approvalNumber = cancelApprovalNumber
            it.providerTransactionId = providerTransactionId
            it.originalProviderTransactionId = originalProviderTransactionId
            it.institutionTransactionDate = institutionTransactionDate
            it.institutionBusinessDate = institutionBusinessDate
            it.sourceAttemptId = sourceAttemptId
            it.sourceKind = sourceKind
            it.sourceResultId = sourceResultId
            it.recordedBy = recordedBy
            it.confirmedAt = confirmedAt
        }

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

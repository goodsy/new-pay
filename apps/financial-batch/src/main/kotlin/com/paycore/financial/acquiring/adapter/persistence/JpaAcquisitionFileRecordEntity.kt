package com.paycore.financial.acquiring.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * acquisition_file_record 행(membership — 파일 v1 D 줄 값 그대로, append-only). `@Immutable` + updatable=false, 파일 행과 연관(`@ManyToOne`)을 두지 않는다
 * (file_id 값만 — 지연 로딩·cascade 경로 없음). UNIQUE(transaction_id, record_type, generation)이 이중 매입의 DB 방어다.
 */
@Entity
@Immutable
@IdClass(AcquisitionFileRecordKey::class)
@Table(name = "acquisition_file_record")
class JpaAcquisitionFileRecordEntity protected constructor() {
    @field:Id
    @field:Column(name = "file_id", nullable = false, updatable = false, length = 64)
    lateinit var fileId: String
        protected set

    @field:Id
    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    @field:Column(name = "record_type", nullable = false, updatable = false, length = 8)
    lateinit var recordType: String
        protected set

    @field:Column(name = "transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var transactionId: String
        protected set

    @field:Column(name = "original_transaction_id", updatable = false, length = 64)
    var originalTransactionId: String? = null
        protected set

    @field:Column(name = "merchant_ref", nullable = false, updatable = false, length = 64)
    lateinit var merchantRef: String
        protected set

    @field:Column(name = "approval_number", nullable = false, updatable = false, length = 64)
    lateinit var approvalNumber: String
        protected set

    @field:Column(name = "provider_transaction_id", nullable = false, updatable = false, length = 64)
    lateinit var providerTransactionId: String
        protected set

    @field:Column(name = "original_provider_transaction_id", updatable = false, length = 64)
    var originalProviderTransactionId: String? = null
        protected set

    @field:Column(name = "institution_transaction_date", nullable = false, updatable = false)
    lateinit var institutionTransactionDate: LocalDate
        protected set

    @field:Column(name = "event_at", nullable = false, updatable = false)
    lateinit var eventAt: Instant
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "approved_amount_minor", nullable = false, updatable = false)
    var approvedAmountMinor: Long = 0
        protected set

    @field:Column(name = "netted_cancel_amount_minor", nullable = false, updatable = false)
    var nettedCancelAmountMinor: Long = 0
        protected set

    @field:Column(name = "record_amount_minor", nullable = false, updatable = false)
    var recordAmountMinor: Long = 0
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

    @field:Column(name = "issuer_code", nullable = false, updatable = false, length = 32)
    lateinit var issuerCode: String
        protected set

    @field:Column(name = "generation", nullable = false, updatable = false)
    var generation: Int = 0
        protected set

    @field:Column(name = "reacquisition_of", updatable = false, length = 64)
    var reacquisitionOf: String? = null
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaAcquisitionFileRecordEntity(fileId=$fileId, seq=$seq, type=$recordType)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            fileId: String, seq: Int, recordType: String, transactionId: String, originalTransactionId: String?, merchantRef: String, approvalNumber: String,
            providerTransactionId: String, originalProviderTransactionId: String?, institutionTransactionDate: LocalDate, eventAt: Instant, currency: String,
            approvedAmountMinor: Long, nettedCancelAmountMinor: Long, recordAmountMinor: Long, planKind: String, installmentMonths: Int?, cardType: String,
            issuerCode: String, generation: Int, reacquisitionOf: String?,
        ) = JpaAcquisitionFileRecordEntity().also {
            it.fileId = fileId
            it.seq = seq
            it.recordType = recordType
            it.transactionId = transactionId
            it.originalTransactionId = originalTransactionId
            it.merchantRef = merchantRef
            it.approvalNumber = approvalNumber
            it.providerTransactionId = providerTransactionId
            it.originalProviderTransactionId = originalProviderTransactionId
            it.institutionTransactionDate = institutionTransactionDate
            it.eventAt = eventAt
            it.currency = currency
            it.approvedAmountMinor = approvedAmountMinor
            it.nettedCancelAmountMinor = nettedCancelAmountMinor
            it.recordAmountMinor = recordAmountMinor
            it.planKind = planKind
            it.installmentMonths = installmentMonths
            it.cardType = cardType
            it.issuerCode = issuerCode
            it.generation = generation
            it.reacquisitionOf = reacquisitionOf
        }
    }
}

/** 레코드 복합 키(file_id, seq) — `@IdClass`. */
class AcquisitionFileRecordKey() : Serializable {
    var fileId: String = ""
    var seq: Int = 0

    constructor(fileId: String, seq: Int) : this() {
        this.fileId = fileId
        this.seq = seq
    }

    override fun equals(other: Any?): Boolean = other is AcquisitionFileRecordKey && other.fileId == fileId && other.seq == seq

    override fun hashCode(): Int = 31 * fileId.hashCode() + seq

    override fun toString(): String = "$fileId#$seq"
}

/** 행 → Domain 레코드(Domain 생성자를 다시 거친다 — 저장 값이 계약을 어기면 [com.paycore.financial.acquiring.domain.AcquisitionContractViolation]). */
internal fun JpaAcquisitionFileRecordEntity.toDomain() = com.paycore.financial.acquiring.domain.AcquisitionRecord(
    seq = seq, type = com.paycore.financial.acquiring.domain.AcquisitionRecordType.valueOf(recordType), transactionId = transactionId,
    originalTransactionId = originalTransactionId, merchantRef = merchantRef, approvalNumber = approvalNumber, providerTransactionId = providerTransactionId,
    originalProviderTransactionId = originalProviderTransactionId, institutionTransactionDate = institutionTransactionDate, eventAt = eventAt, currency = currency,
    approvedAmountMinor = approvedAmountMinor, nettedCancelAmountMinor = nettedCancelAmountMinor, recordAmountMinor = recordAmountMinor, planKind = planKind,
    installmentMonths = installmentMonths, cardType = cardType, issuerCode = issuerCode, generation = generation, reacquisitionOf = reacquisitionOf,
)

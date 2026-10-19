package com.paycore.simulator.van.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant
import java.time.LocalDate

/**
 * van_ledger_entry 행(append-only — `@Immutable`: Hibernate가 변경 감지 UPDATE를 만들지 않는다. DB 권한도 SELECT·INSERT만 — AUTH-001 AU-20). 기관 금융 사실 원본이며
 * PK는 기관 ID(결정 `vd_`·종결 `vc_`)라 저장 전후 같다. namespace UNIQUE가 결정과 종결 표지의 동시 INSERT를 하나로 만든다.
 */
@Entity
@Immutable
@Table(name = "van_ledger_entry")
class JpaVanLedgerEntryEntity protected constructor() {
    @field:Id
    @field:Column(name = "entry_id", nullable = false, updatable = false, columnDefinition = "char(29)")
    lateinit var entryId: String
        protected set

    @field:Column(name = "client_id", nullable = false, updatable = false, length = 64)
    lateinit var clientId: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "institution_account", nullable = false, updatable = false, length = 64)
    lateinit var institutionAccount: String
        protected set

    @field:Column(name = "operation", nullable = false, updatable = false, length = 16)
    lateinit var operation: String
        protected set

    @field:Column(name = "request_id", nullable = false, updatable = false, columnDefinition = "char(29)")
    lateinit var requestId: String
        protected set

    @field:Column(name = "kind", nullable = false, updatable = false, length = 8)
    lateinit var kind: String
        protected set

    @field:Column(name = "payload_hash", updatable = false, columnDefinition = "char(64)")
    var payloadHash: String? = null
        protected set

    @field:Column(name = "evidence_ref", updatable = false, length = 128)
    var evidenceRef: String? = null
        protected set

    @field:Column(name = "card_ref", updatable = false, length = 32)
    var cardRef: String? = null
        protected set

    @field:Column(name = "outcome", updatable = false, length = 8)
    var outcome: String? = null
        protected set

    @field:Column(name = "approval_number", updatable = false, length = 32)
    var approvalNumber: String? = null
        protected set

    @field:Column(name = "provider_transaction_id", updatable = false, length = 64)
    var providerTransactionId: String? = null
        protected set

    @field:Column(name = "approved_amount_minor", updatable = false)
    var approvedAmountMinor: Long? = null
        protected set

    @field:Column(name = "currency", updatable = false, columnDefinition = "char(3)")
    var currency: String? = null
        protected set

    @field:Column(name = "plan_kind", updatable = false, length = 12)
    var planKind: String? = null
        protected set

    @field:Column(name = "installment_months", updatable = false)
    var installmentMonths: Int? = null
        protected set

    @field:Column(name = "card_type", updatable = false, length = 32)
    var cardType: String? = null
        protected set

    @field:Column(name = "institution_transaction_date", updatable = false)
    var institutionTransactionDate: LocalDate? = null
        protected set

    @field:Column(name = "institution_business_date", updatable = false)
    var institutionBusinessDate: LocalDate? = null
        protected set

    @field:Column(name = "decline_category", updatable = false, length = 32)
    var declineCategory: String? = null
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return entryId == (other as JpaVanLedgerEntryEntity).entryId
    }

    override fun hashCode(): Int = entryId.hashCode()

    override fun toString(): String = "JpaVanLedgerEntryEntity(entryId=$entryId, kind=$kind, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            entryId: String, clientId: String, environment: String, institutionAccount: String, operation: String, requestId: String, kind: String,
            payloadHash: String?, evidenceRef: String?, cardRef: String?, outcome: String?, approvalNumber: String?, providerTransactionId: String?,
            approvedAmountMinor: Long?, currency: String?, planKind: String?, installmentMonths: Int?, cardType: String?, institutionTransactionDate: LocalDate?,
            institutionBusinessDate: LocalDate?, declineCategory: String?, createdAt: Instant,
        ) = JpaVanLedgerEntryEntity().also {
            it.entryId = entryId
            it.clientId = clientId
            it.environment = environment
            it.institutionAccount = institutionAccount
            it.operation = operation
            it.requestId = requestId
            it.kind = kind
            it.payloadHash = payloadHash
            it.evidenceRef = evidenceRef
            it.cardRef = cardRef
            it.outcome = outcome
            it.approvalNumber = approvalNumber
            it.providerTransactionId = providerTransactionId
            it.approvedAmountMinor = approvedAmountMinor
            it.currency = currency
            it.planKind = planKind
            it.installmentMonths = installmentMonths
            it.cardType = cardType
            it.institutionTransactionDate = institutionTransactionDate
            it.institutionBusinessDate = institutionBusinessDate
            it.declineCategory = declineCategory
            it.createdAt = createdAt
        }
    }
}

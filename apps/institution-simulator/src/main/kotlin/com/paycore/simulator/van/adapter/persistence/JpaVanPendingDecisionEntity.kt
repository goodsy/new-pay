package com.paycore.simulator.van.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/**
 * van_pending_decision 행(PENDING 예약 — AUTH-001 §5.5). 바뀌는 열은 state·resolved_entry_id·updated_at뿐이며 확정 반영은 저장소의 조건부 UPDATE(`state = 'PENDING'`)로만
 * 한다(경합하면 0행 → 재판정). 나머지 열은 `updatable = false`.
 */
@Entity
@Table(name = "van_pending_decision")
class JpaVanPendingDecisionEntity protected constructor() {
    @field:Id
    @field:Column(name = "pending_id", nullable = false, updatable = false, columnDefinition = "char(29)")
    lateinit var pendingId: String
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

    @field:Column(name = "payload_hash", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var payloadHash: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "card_ref", updatable = false, length = 32)
    var cardRef: String? = null
        protected set

    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "plan_kind", nullable = false, updatable = false, length = 12)
    lateinit var planKind: String
        protected set

    @field:Column(name = "installment_months", updatable = false)
    var installmentMonths: Int? = null
        protected set

    @field:Column(name = "card_type", nullable = false, updatable = false, length = 32)
    lateinit var cardType: String
        protected set

    @field:Column(name = "resolve_after", nullable = false, updatable = false)
    lateinit var resolveAfter: Instant
        protected set

    @field:Column(name = "state", nullable = false, length = 8)
    lateinit var state: String
        protected set

    @field:Column(name = "resolved_entry_id", columnDefinition = "char(29)")
    var resolvedEntryId: String? = null
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return pendingId == (other as JpaVanPendingDecisionEntity).pendingId
    }

    override fun hashCode(): Int = pendingId.hashCode()

    override fun toString(): String = "JpaVanPendingDecisionEntity(pendingId=$pendingId, state=$state)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            pendingId: String, clientId: String, environment: String, institutionAccount: String, operation: String, requestId: String, payloadHash: String,
            evidenceRef: String, cardRef: String?, amountMinor: Long, currency: String, planKind: String, installmentMonths: Int?, cardType: String, resolveAfter: Instant,
            createdAt: Instant,
        ) = JpaVanPendingDecisionEntity().also {
            it.pendingId = pendingId
            it.clientId = clientId
            it.environment = environment
            it.institutionAccount = institutionAccount
            it.operation = operation
            it.requestId = requestId
            it.payloadHash = payloadHash
            it.evidenceRef = evidenceRef
            it.cardRef = cardRef
            it.amountMinor = amountMinor
            it.currency = currency
            it.planKind = planKind
            it.installmentMonths = installmentMonths
            it.cardType = cardType
            it.resolveAfter = resolveAfter
            it.state = "PENDING"
            it.resolvedEntryId = null
            it.createdAt = createdAt
            it.updatedAt = createdAt
        }
    }
}

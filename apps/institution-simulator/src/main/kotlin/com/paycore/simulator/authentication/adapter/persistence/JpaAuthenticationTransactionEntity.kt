package com.paycore.simulator.authentication.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.time.Instant

/**
 * authentication_transaction 행 — 순수 domain [com.paycore.simulator.authentication.domain.AuthenticationTransaction]과 분리된 저장 모양(PayCore 관례와 같다:
 * data class 아님, 식별자 equals, 생성 시 불변 `updatable = false`, toString에 state·URL 없음).
 */
@Entity
@Table(name = "authentication_transaction")
class JpaAuthenticationTransactionEntity protected constructor() {
    @field:Id
    @field:Column(name = "transaction_id", nullable = false, updatable = false, columnDefinition = "char(26)")
    lateinit var transactionId: String
        protected set

    @field:Column(name = "client_id", nullable = false, updatable = false, length = 64)
    lateinit var clientId: String
        protected set

    @field:Column(name = "state", nullable = false, updatable = false, columnDefinition = "char(43)")
    lateinit var state: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "merchant_ref", nullable = false, updatable = false, length = 64)
    lateinit var merchantRef: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
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

    @field:Column(name = "card_company", nullable = false, updatable = false, length = 32)
    lateinit var cardCompany: String
        protected set

    @field:Column(name = "return_url", nullable = false, updatable = false, length = 2048)
    lateinit var returnUrl: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "card_ref", length = 32)
    var cardRef: String? = null

    @field:Column(name = "result_ref", columnDefinition = "char(26)")
    var resultRef: String? = null

    @field:Column(name = "evidence_ref", length = 128)
    var evidenceRef: String? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "expires_at", nullable = false, updatable = false)
    lateinit var expiresAt: Instant
        protected set

    @field:Column(name = "completed_at")
    var completedAt: Instant? = null

    @field:Column(name = "valid_until")
    var validUntil: Instant? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return transactionId == (other as JpaAuthenticationTransactionEntity).transactionId
    }

    override fun hashCode(): Int = transactionId.hashCode()

    override fun toString(): String = "JpaAuthenticationTransactionEntity(transactionId=$transactionId, status=$status)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            transactionId: String, clientId: String, state: String, paymentId: String, merchantRef: String, environment: String, amountMinor: Long,
            currency: String, planKind: String, installmentMonths: Int?, cardCompany: String, returnUrl: String, createdAt: Instant, expiresAt: Instant,
        ) = JpaAuthenticationTransactionEntity().also {
            it.transactionId = transactionId
            it.clientId = clientId
            it.state = state
            it.paymentId = paymentId
            it.merchantRef = merchantRef
            it.environment = environment
            it.amountMinor = amountMinor
            it.currency = currency
            it.planKind = planKind
            it.installmentMonths = installmentMonths
            it.cardCompany = cardCompany
            it.returnUrl = returnUrl
            it.status = "REGISTERED"
            it.createdAt = createdAt
            it.expiresAt = expiresAt
        }
    }
}

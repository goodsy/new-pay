package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/*
 * MASTER-SNAPSHOT-002 표의 JPA Entity(adapter 전용 — 순수 Domain과 분리, Mapper가 변환). 범위·값 컬럼은 updatable=false(정책 값은 새 행으로만 바뀐다),
 * 종료·상태 컬럼만 변경 감지 대상이다. append-only 표(policy_version·card_route_activation_event)는 @Immutable + persist만(save/merge 금지 — runtime
 * 계정에 UPDATE 권한이 없어 1142). 생성 컬럼(open_*_key)은 매핑하지 않는다. Kotlin data class를 쓰지 않는다(식별자 기반 equals·프록시 안전).
 */

/** transaction_limit_policy 행. 종료는 변경 감지 + `@Version`(effective_to·ended_*만). */
@Entity
@Table(name = "transaction_limit_policy")
class JpaTransactionLimitPolicyEntity protected constructor() {
    @field:Id
    @field:Column(name = "policy_id", nullable = false, updatable = false, length = 64)
    lateinit var policyId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "scope_kind", nullable = false, updatable = false, length = 16)
    lateinit var scopeKind: String
        protected set

    @field:Column(name = "contract_id", updatable = false, length = 64)
    var contractId: String? = null
        protected set

    @field:Column(name = "agreement_id", updatable = false, length = 64)
    var agreementId: String? = null
        protected set

    @field:Column(name = "merchant_account_id", updatable = false, length = 64)
    var merchantAccountId: String? = null
        protected set

    @field:Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currencyCode: String
        protected set

    @field:Column(name = "per_tx_kind", nullable = false, updatable = false, length = 16)
    lateinit var perTxKind: String
        protected set

    @field:Column(name = "per_tx_amount", updatable = false)
    var perTxAmount: Long? = null
        protected set

    @field:Column(name = "daily_kind", nullable = false, updatable = false, length = 16)
    lateinit var dailyKind: String
        protected set

    @field:Column(name = "daily_amount", updatable = false)
    var dailyAmount: Long? = null
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to")
    var effectiveTo: Instant? = null

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "created_by", nullable = false, updatable = false, length = 64)
    lateinit var createdBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "ended_at")
    var endedAt: Instant? = null

    @field:Column(name = "ended_by", length = 64)
    var endedBy: String? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && policyId == (other as JpaTransactionLimitPolicyEntity).policyId)

    override fun hashCode(): Int = policyId.hashCode()

    override fun toString(): String = "JpaTransactionLimitPolicyEntity(policyId=$policyId, scope=$scopeKind, currency=$currencyCode)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            merchantId: String,
            scopeKind: String,
            contractId: String?,
            agreementId: String?,
            accountId: String?,
            currency: String,
            perTx: Pair<String, Long?>,
            daily: Pair<String, Long?>,
            from: Instant,
            to: Instant?,
            reason: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaTransactionLimitPolicyEntity().also {
            it.policyId = id
            it.merchantId = merchantId
            it.scopeKind = scopeKind
            it.contractId = contractId
            it.agreementId = agreementId
            it.merchantAccountId = accountId
            it.currencyCode = currency
            it.perTxKind = perTx.first
            it.perTxAmount = perTx.second
            it.dailyKind = daily.first
            it.dailyAmount = daily.second
            it.effectiveFrom = from
            it.effectiveTo = to
            it.reason = reason
            it.createdAt = at
            it.createdBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.io.Serializable
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

/** policy_version 행(append-only 식별 등록부, D-MS2-003). */
@Entity
@Immutable
@Table(name = "policy_version")
class JpaPolicyVersionEntity protected constructor() {
    @field:Id
    @field:Column(name = "policy_version_id", nullable = false, updatable = false, length = 64)
    lateinit var policyVersionId: String
        protected set

    @field:Column(name = "policy_kind", nullable = false, updatable = false, length = 24)
    lateinit var policyKind: String
        protected set

    @field:Column(name = "policy_code", nullable = false, updatable = false, length = 32)
    lateinit var policyCode: String
        protected set

    @field:Column(name = "version_no", nullable = false, updatable = false)
    var versionNo: Int = 0
        protected set

    @field:Column(name = "content_status", nullable = false, updatable = false, length = 16)
    lateinit var contentStatus: String
        protected set

    @field:Column(name = "content_hash", updatable = false, columnDefinition = "char(64)")
    var contentHash: String? = null
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "registered_at", nullable = false, updatable = false)
    lateinit var registeredAt: Instant
        protected set

    @field:Column(name = "registered_by", nullable = false, updatable = false, length = 64)
    lateinit var registeredBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && policyVersionId == (other as JpaPolicyVersionEntity).policyVersionId)

    override fun hashCode(): Int = policyVersionId.hashCode()

    override fun toString(): String = "JpaPolicyVersionEntity(id=$policyVersionId, kind=$policyKind, code=$policyCode, no=$versionNo)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            kind: String,
            code: String,
            versionNo: Int,
            contentStatus: String,
            contentHash: String?,
            reason: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaPolicyVersionEntity().also {
            it.policyVersionId = id
            it.policyKind = kind
            it.policyCode = code
            it.versionNo = versionNo
            it.contentStatus = contentStatus
            it.contentHash = contentHash
            it.reason = reason
            it.registeredAt = at
            it.registeredBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

/** policy_assignment 행. 종료는 변경 감지 + `@Version`. */
@Entity
@Table(name = "policy_assignment")
class JpaPolicyAssignmentEntity protected constructor() {
    @field:Id
    @field:Column(name = "assignment_id", nullable = false, updatable = false, length = 64)
    lateinit var assignmentId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "agreement_id", nullable = false, updatable = false, length = 64)
    lateinit var agreementId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currencyCode: String
        protected set

    @field:Column(name = "policy_kind", nullable = false, updatable = false, length = 24)
    lateinit var policyKind: String
        protected set

    @field:Column(name = "policy_version_id", nullable = false, updatable = false, length = 64)
    lateinit var policyVersionId: String
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
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && assignmentId == (other as JpaPolicyAssignmentEntity).assignmentId)

    override fun hashCode(): Int = assignmentId.hashCode()

    override fun toString(): String = "JpaPolicyAssignmentEntity(id=$assignmentId, kind=$policyKind, currency=$currencyCode)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            merchantId: String,
            agreementId: String,
            accountId: String,
            currency: String,
            kind: String,
            policyVersionId: String,
            from: Instant,
            to: Instant?,
            reason: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaPolicyAssignmentEntity().also {
            it.assignmentId = id
            it.merchantId = merchantId
            it.agreementId = agreementId
            it.merchantAccountId = accountId
            it.currencyCode = currency
            it.policyKind = kind
            it.policyVersionId = policyVersionId
            it.effectiveFrom = from
            it.effectiveTo = to
            it.reason = reason
            it.createdAt = at
            it.createdBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

/** card_route_activation 행. 상태 전이는 변경 감지 + `@Version`. [institutionMerchantRef]는 toString에 넣지 않는다(D-MS2-005). */
@Entity
@Table(name = "card_route_activation")
class JpaCardRouteActivationEntity protected constructor() {
    @field:Id
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    lateinit var paymentMethod: String
        protected set

    @field:Column(name = "acquirer_code", nullable = false, updatable = false, length = 32)
    lateinit var acquirerCode: String
        protected set

    @field:Column(name = "route_ref", nullable = false, updatable = false, length = 64)
    lateinit var routeRef: String
        protected set

    @field:Column(name = "institution_merchant_ref", nullable = false, updatable = false, length = 64)
    lateinit var institutionMerchantRef: String
        protected set

    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "evidence_ref", length = 128)
    var evidenceRef: String? = null

    @field:Column(name = "registered_at", nullable = false, updatable = false)
    lateinit var registeredAt: Instant
        protected set

    @field:Column(name = "registered_by", nullable = false, updatable = false, length = 64)
    lateinit var registeredBy: String
        protected set

    @field:Column(name = "status_changed_at", nullable = false)
    lateinit var statusChangedAt: Instant

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && activationId == (other as JpaCardRouteActivationEntity).activationId)

    override fun hashCode(): Int = activationId.hashCode()

    override fun toString(): String = "JpaCardRouteActivationEntity(id=$activationId, acquirer=$acquirerCode, status=$status)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            accountId: String,
            merchantId: String,
            method: String,
            acquirer: String,
            route: String,
            institutionRef: String,
            status: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaCardRouteActivationEntity().also {
            it.activationId = id
            it.merchantAccountId = accountId
            it.merchantId = merchantId
            it.paymentMethod = method
            it.acquirerCode = acquirer
            it.routeRef = route
            it.institutionMerchantRef = institutionRef
            it.status = status
            it.registeredAt = at
            it.registeredBy = by
            it.statusChangedAt = at
            it.requestRecordId = requestRecordId
        }
    }
}

/** (activation_id, seq) 복합 키. */
@Embeddable
class CardRouteEventKey protected constructor() : Serializable {
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is CardRouteEventKey && activationId == other.activationId && seq == other.seq)

    override fun hashCode(): Int = 31 * activationId.hashCode() + seq

    override fun toString(): String = "CardRouteEventKey($activationId#$seq)"

    companion object {
        fun of(activationId: String, seq: Int) = CardRouteEventKey().also {
            it.activationId = activationId
            it.seq = seq
        }
    }
}

/** card_route_activation_event 행(append-only 전이 이력). */
@Entity
@Immutable
@Table(name = "card_route_activation_event")
class JpaCardRouteEventEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: CardRouteEventKey
        protected set

    @field:Column(name = "from_status", updatable = false, length = 16)
    var fromStatus: String? = null
        protected set

    @field:Column(name = "to_status", nullable = false, updatable = false, length = 16)
    lateinit var toStatus: String
        protected set

    @field:Column(name = "evidence_ref", updatable = false, length = 128)
    var evidenceRef: String? = null
        protected set

    @field:Column(name = "reason", updatable = false, length = 200)
    var reason: String? = null
        protected set

    @field:Column(name = "actor_ref", nullable = false, updatable = false, length = 64)
    lateinit var actorRef: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "occurred_at", nullable = false, updatable = false)
    lateinit var occurredAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaCardRouteEventEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaCardRouteEventEntity(key=$key, to=$toStatus)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            key: CardRouteEventKey,
            from: String?,
            to: String,
            evidenceRef: String?,
            reason: String?,
            actor: String,
            requestRecordId: String,
            at: Instant,
        ) = JpaCardRouteEventEntity().also {
            it.key = key
            it.fromStatus = from
            it.toStatus = to
            it.evidenceRef = evidenceRef
            it.reason = reason
            it.actorRef = actor
            it.requestRecordId = requestRecordId
            it.occurredAt = at
        }
    }
}

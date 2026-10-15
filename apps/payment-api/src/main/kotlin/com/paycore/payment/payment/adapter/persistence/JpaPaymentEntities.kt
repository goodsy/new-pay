package com.paycore.payment.payment.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant

/*
 * JPA Entity 공통 선택(CP-DB-001, docs/repository/ARCHITECTURE-CONVENTIONS.md §9):
 * - 순수 domain(Payment·OrderPaymentControl)과 분리된 저장 전용 클래스다. domain에는 JPA annotation이 없고 Mapper가 변환한다.
 * - data class를 쓰지 않는다: equals/hashCode는 바뀌지 않는 식별자(업무 ID·자연 키)만 보고, 프록시는 Hibernate.getClass로 풀어 비교한다.
 *   toString에는 식별자만 둔다(연관 지연 로딩·민감 값 노출 방지).
 * - 필드는 `protected set`의 var다(Hibernate가 읽어 올 때 값을 채우는 필드가 final이 아니게 — JDK의 final 필드 변경 제한에 대비).
 *   생성은 companion factory만 쓴다. kotlin-jpa plugin이 Hibernate용 기본 생성자를 만든다.
 * - 생성 시 불변 컬럼은 `updatable = false`: Mapper 실수로도 UPDATE 문에 들어가지 않는다(생성 사실 보존).
 * - 연관 매핑(@OneToMany 등)을 쓰지 않는다. 종료 근거·사건 보류는 append-only 자식 행이라 adapter가 명시적으로 INSERT만 한다
 *   (cascade·orphanRemoval·지연 로딩이 자식 행을 지우거나 N+1을 만드는 일을 구조적으로 피한다).
 * - `@Version`은 동시 수정 감지 기술 값이다. 업무 generation(slot_generation/generation)과 따로 둔다.
 */

/** payment 행. */
@Entity
@Table(name = "payment")
class JpaPaymentEntity protected constructor() {
    @field:Id
    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "payment_key", nullable = false, updatable = false, length = 64)
    lateinit var paymentKey: String
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    /** 통화 minor unit 정수. BigDecimal·Double 매핑을 쓰지 않는다(D-CPC-005). */
    @field:Column(name = "amount_minor", nullable = false, updatable = false)
    var amountMinor: Long = 0
        protected set

    @field:Column(name = "currency", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currency: String
        protected set

    @field:Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    lateinit var paymentMethod: String
        protected set

    @field:Column(name = "order_name", updatable = false, length = 100)
    var orderName: String? = null
        protected set

    @field:Column(name = "order_intent_fingerprint", nullable = false, updatable = false, length = 80)
    lateinit var orderIntentFingerprint: String
        protected set

    @field:Column(name = "slot_generation", nullable = false, updatable = false)
    var slotGeneration: Long = 0
        protected set

    /** 유일하게 바뀌는 업무 컬럼(상태 전이). */
    @field:Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @field:Column(name = "eligibility_snapshot_version", nullable = false, updatable = false, length = 64)
    lateinit var eligibilitySnapshotVersion: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    /** 생성 행위자 Credential keyId(V5, D-CPA-001). 생성 시 불변. V5 이전 행만 null. */
    @field:Column(name = "created_by_credential_id", updatable = false, length = 32)
    var createdByCredentialId: String? = null
        protected set

    /** null이면 새 Entity(persist), INSERT 시 Hibernate가 0을 넣고 UPDATE마다 `WHERE version = ?`로 1씩 올린다. 직접 바꾸지 않는다. */
    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return paymentId == (other as JpaPaymentEntity).paymentId
    }

    override fun hashCode(): Int = paymentId.hashCode()

    override fun toString(): String = "JpaPaymentEntity(paymentId=$paymentId, status=$status, version=$version)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            paymentId: String,
            paymentKey: String,
            environment: String,
            merchantAccountId: String,
            merchantOrderId: String,
            merchantSiteId: String,
            amountMinor: Long,
            currency: String,
            paymentMethod: String,
            orderName: String?,
            orderIntentFingerprint: String,
            slotGeneration: Long,
            status: String,
            eligibilitySnapshotVersion: String,
            createdAt: Instant,
            createdByCredentialId: String?,
        ): JpaPaymentEntity = JpaPaymentEntity().also {
            it.paymentId = paymentId
            it.paymentKey = paymentKey
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
            it.merchantSiteId = merchantSiteId
            it.amountMinor = amountMinor
            it.currency = currency
            it.paymentMethod = paymentMethod
            it.orderName = orderName
            it.orderIntentFingerprint = orderIntentFingerprint
            it.slotGeneration = slotGeneration
            it.status = status
            it.eligibilitySnapshotVersion = eligibilitySnapshotVersion
            it.createdAt = createdAt
            it.createdByCredentialId = createdByCredentialId
        }
    }
}

/** payment_termination_evidence 복합 키(payment_id, seq). 값 객체라 equals/hashCode를 두 필드로 정의한다. */
@Embeddable
class TerminationEvidenceKey protected constructor() : Serializable {
    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other is TerminationEvidenceKey && paymentId == other.paymentId && seq == other.seq)

    override fun hashCode(): Int = 31 * paymentId.hashCode() + seq

    override fun toString(): String = "TerminationEvidenceKey($paymentId#$seq)"

    companion object {
        private const val serialVersionUID = 1L

        fun of(paymentId: String, seq: Int): TerminationEvidenceKey = TerminationEvidenceKey().also {
            it.paymentId = paymentId
            it.seq = seq
        }
    }
}

/** 종료 근거 한 건(append-only 자식 행, INSERT만). */
@Entity
@Table(name = "payment_termination_evidence")
class JpaPaymentTerminationEvidenceEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: TerminationEvidenceKey
        protected set

    @field:Column(name = "evidence", nullable = false, updatable = false, length = 48)
    lateinit var evidence: String
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return key == (other as JpaPaymentTerminationEvidenceEntity).key
    }

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaPaymentTerminationEvidenceEntity($key)"

    companion object {
        fun newRow(paymentId: String, seq: Int, evidence: String): JpaPaymentTerminationEvidenceEntity =
            JpaPaymentTerminationEvidenceEntity().also {
                it.key = TerminationEvidenceKey.of(paymentId, seq)
                it.evidence = evidence
            }
    }
}

/** order_payment_control 복합 PK = OrderKey(environment, MID, merchantOrderId). */
@Embeddable
class OrderKeyColumns protected constructor() : Serializable {
    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is OrderKeyColumns && environment == other.environment && merchantAccountId == other.merchantAccountId &&
                merchantOrderId == other.merchantOrderId
            )

    override fun hashCode(): Int = listOf(environment, merchantAccountId, merchantOrderId).hashCode()

    override fun toString(): String = "OrderKeyColumns($environment/$merchantAccountId/$merchantOrderId)"

    companion object {
        private const val serialVersionUID = 1L

        fun of(environment: String, merchantAccountId: String, merchantOrderId: String): OrderKeyColumns = OrderKeyColumns().also {
            it.environment = environment
            it.merchantAccountId = merchantAccountId
            it.merchantOrderId = merchantOrderId
        }
    }
}

/** order_payment_control 행. 최초 INSERT는 adapter의 native INSERT … ON DUPLICATE KEY(경쟁 직렬화)이며 JPA는 읽기·갱신만 한다. */
@Entity
@Table(name = "order_payment_control")
class JpaOrderPaymentControlEntity protected constructor() {
    @field:EmbeddedId
    lateinit var orderKey: OrderKeyColumns
        protected set

    @field:Column(name = "fixed_intent_fingerprint", nullable = false, updatable = false, length = 80)
    lateinit var fixedIntentFingerprint: String
        protected set

    @field:Column(name = "current_payment_id", nullable = false, length = 64)
    lateinit var currentPaymentId: String

    @field:Column(name = "generation", nullable = false)
    var generation: Long = 0

    @field:Column(name = "authorized_payment_id", length = 64)
    var authorizedPaymentId: String? = null

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    @field:Column(name = "updated_at", nullable = false)
    lateinit var updatedAt: Instant

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null
        protected set

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return orderKey == (other as JpaOrderPaymentControlEntity).orderKey
    }

    override fun hashCode(): Int = orderKey.hashCode()

    override fun toString(): String = "JpaOrderPaymentControlEntity($orderKey, generation=$generation, version=$version)"
}

/** incident_hold 행(append-only, INSERT만). 유일성 UNIQUE(OrderKey, payment_id, reason). */
@Entity
@Table(name = "incident_hold")
class JpaIncidentHoldEntity protected constructor() {
    /** DB가 부여하는 순서 키(auto increment). 저장 전에는 null이라 equals/hashCode에 쓰지 않는다. */
    @field:Id
    @field:jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "environment", nullable = false, updatable = false, length = 4)
    lateinit var environment: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_order_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantOrderId: String
        protected set

    @field:Column(name = "payment_id", nullable = false, updatable = false, length = 64)
    lateinit var paymentId: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 40)
    lateinit var reason: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    /** 자연 키(OrderKey, paymentId, reason) — 저장 전후로 같은 값이라 Set에 넣어도 안정적이다. */
    private fun naturalKey() = listOf(environment, merchantAccountId, merchantOrderId, paymentId, reason)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        return naturalKey() == (other as JpaIncidentHoldEntity).naturalKey()
    }

    override fun hashCode(): Int = naturalKey().hashCode()

    override fun toString(): String = "JpaIncidentHoldEntity(id=$id, paymentId=$paymentId, reason=$reason)"

    companion object {
        fun newRow(order: OrderKeyColumns, paymentId: String, reason: String, createdAt: Instant): JpaIncidentHoldEntity =
            JpaIncidentHoldEntity().also {
                it.environment = order.environment
                it.merchantAccountId = order.merchantAccountId
                it.merchantOrderId = order.merchantOrderId
                it.paymentId = paymentId
                it.reason = reason
                it.createdAt = createdAt
            }
    }
}

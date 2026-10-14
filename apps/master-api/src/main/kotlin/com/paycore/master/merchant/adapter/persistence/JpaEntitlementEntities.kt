package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant

/*
 * MASTER-SNAPSHOT-001 표의 JPA Entity(adapter 전용 — 순수 Domain과 분리, Mapper가 변환). append-only 표는 모든 컬럼 updatable=false이며
 * Store는 persist만 한다(save/merge 금지 — runtime 계정에 UPDATE 권한이 없어 1142). 생성 컬럼(open_*_key)은 매핑하지 않는다.
 */

/** (activation_id, seq) 복합 키. */
@Embeddable
class ActivationEventKey protected constructor() : Serializable {
    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is ActivationEventKey && activationId == other.activationId && seq == other.seq)

    override fun hashCode(): Int = 31 * activationId.hashCode() + seq

    override fun toString(): String = "ActivationEventKey($activationId#$seq)"

    companion object {
        fun of(activationId: String, seq: Int) = ActivationEventKey().also {
            it.activationId = activationId
            it.seq = seq
        }
    }
}

/** payment_method_activation_event 행(append-only 전이 이력). */
@Entity
@Table(name = "payment_method_activation_event")
class JpaActivationEventEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: ActivationEventKey
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
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaActivationEventEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaActivationEventEntity(key=$key, to=$toStatus)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            key: ActivationEventKey,
            from: String?,
            to: String,
            evidenceRef: String?,
            reason: String?,
            actor: String,
            requestRecordId: String,
            at: Instant,
        ) = JpaActivationEventEntity().also {
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

/** site_activation_confirmation 행(append-only). */
@Entity
@Table(name = "site_activation_confirmation")
class JpaSiteActivationConfirmationEntity protected constructor() {
    @field:Id
    @field:Column(name = "confirmation_id", nullable = false, updatable = false, length = 64)
    lateinit var confirmationId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    lateinit var paymentMethod: String
        protected set

    @field:Column(name = "activation_id", nullable = false, updatable = false, length = 64)
    lateinit var activationId: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "confirmed_at", nullable = false, updatable = false)
    lateinit var confirmedAt: Instant
        protected set

    @field:Column(name = "confirmed_by", nullable = false, updatable = false, length = 64)
    lateinit var confirmedBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    @field:Column(name = "confirmation_seq", nullable = false, updatable = false)
    var confirmationSeq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && confirmationId == (other as JpaSiteActivationConfirmationEntity).confirmationId)

    override fun hashCode(): Int = confirmationId.hashCode()

    override fun toString(): String = "JpaSiteActivationConfirmationEntity(confirmationId=$confirmationId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            accountId: String,
            siteId: String,
            merchantId: String,
            method: String,
            activationId: String,
            evidenceRef: String,
            at: Instant,
            by: String,
            requestRecordId: String,
            seq: Int,
        ) = JpaSiteActivationConfirmationEntity().also {
            it.confirmationSeq = seq
            it.confirmationId = id
            it.merchantAccountId = accountId
            it.merchantSiteId = siteId
            it.merchantId = merchantId
            it.paymentMethod = method
            it.activationId = activationId
            it.evidenceRef = evidenceRef
            it.confirmedAt = at
            it.confirmedBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

/** site_activation_confirmation_revocation 행(append-only, 확인당 하나). */
@Entity
@Table(name = "site_activation_confirmation_revocation")
class JpaConfirmationRevocationEntity protected constructor() {
    @field:Id
    @field:Column(name = "confirmation_id", nullable = false, updatable = false, length = 64)
    lateinit var confirmationId: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "revoked_at", nullable = false, updatable = false)
    lateinit var revokedAt: Instant
        protected set

    @field:Column(name = "revoked_by", nullable = false, updatable = false, length = 64)
    lateinit var revokedBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && confirmationId == (other as JpaConfirmationRevocationEntity).confirmationId)

    override fun hashCode(): Int = confirmationId.hashCode()

    override fun toString(): String = "JpaConfirmationRevocationEntity(confirmationId=$confirmationId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(id: String, reason: String, evidenceRef: String, at: Instant, by: String, requestRecordId: String) =
            JpaConfirmationRevocationEntity().also {
                it.confirmationId = id
                it.reason = reason
                it.evidenceRef = evidenceRef
                it.revokedAt = at
                it.revokedBy = by
                it.requestRecordId = requestRecordId
            }
    }
}

/** currency_unit 행(기준정보, 읽기 전용). */
@Entity
@Table(name = "currency_unit")
class JpaCurrencyUnitEntity protected constructor() {
    @field:Id
    @field:Column(name = "currency_code", nullable = false, updatable = false, insertable = false, columnDefinition = "char(3)")
    lateinit var currencyCode: String
        protected set

    @field:Column(name = "minor_unit", nullable = false, updatable = false, insertable = false)
    var minorUnit: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && currencyCode == (other as JpaCurrencyUnitEntity).currencyCode)

    override fun hashCode(): Int = currencyCode.hashCode()

    override fun toString(): String = "JpaCurrencyUnitEntity($currencyCode/$minorUnit)"
}

/** currency_enablement 행. 종료는 변경 감지 + `@Version`(effective_to·ended_*만 갱신 가능). */
@Entity
@Table(name = "currency_enablement")
class JpaCurrencyEnablementEntity protected constructor() {
    @field:Id
    @field:Column(name = "enablement_id", nullable = false, updatable = false, length = 64)
    lateinit var enablementId: String
        protected set

    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "currency_code", nullable = false, updatable = false, columnDefinition = "char(3)")
    lateinit var currencyCode: String
        protected set

    @field:Column(name = "effective_from", nullable = false, updatable = false)
    lateinit var effectiveFrom: Instant
        protected set

    @field:Column(name = "effective_to")
    var effectiveTo: Instant? = null

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
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
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && enablementId == (other as JpaCurrencyEnablementEntity).enablementId)

    override fun hashCode(): Int = enablementId.hashCode()

    override fun toString(): String = "JpaCurrencyEnablementEntity(enablementId=$enablementId, currency=$currencyCode)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            id: String,
            accountId: String,
            currency: String,
            from: Instant,
            to: Instant?,
            evidenceRef: String,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaCurrencyEnablementEntity().also {
            it.enablementId = id
            it.merchantAccountId = accountId
            it.currencyCode = currency
            it.effectiveFrom = from
            it.effectiveTo = to
            it.evidenceRef = evidenceRef
            it.createdAt = at
            it.createdBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

/** (merchant_site_id, seq) 복합 키. */
@Embeddable
class SiteReviewKey protected constructor() : Serializable {
    @field:Column(name = "merchant_site_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantSiteId: String
        protected set

    @field:Column(name = "seq", nullable = false, updatable = false)
    var seq: Int = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is SiteReviewKey && merchantSiteId == other.merchantSiteId && seq == other.seq)

    override fun hashCode(): Int = 31 * merchantSiteId.hashCode() + seq

    override fun toString(): String = "SiteReviewKey($merchantSiteId#$seq)"

    companion object {
        fun of(siteId: String, seq: Int) = SiteReviewKey().also {
            it.merchantSiteId = siteId
            it.seq = seq
        }
    }
}

/** merchant_site_review_decision 행(append-only). */
@Entity
@Table(name = "merchant_site_review_decision")
class JpaSiteReviewDecisionEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: SiteReviewKey
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "decision", nullable = false, updatable = false, length = 16)
    lateinit var decision: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 200)
    lateinit var reason: String
        protected set

    @field:Column(name = "evidence_ref", updatable = false, length = 128)
    var evidenceRef: String? = null
        protected set

    @field:Column(name = "decided_at", nullable = false, updatable = false)
    lateinit var decidedAt: Instant
        protected set

    @field:Column(name = "decided_by", nullable = false, updatable = false, length = 64)
    lateinit var decidedBy: String
        protected set

    @field:Column(name = "request_record_id", nullable = false, updatable = false, length = 64)
    lateinit var requestRecordId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaSiteReviewDecisionEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaSiteReviewDecisionEntity(key=$key, decision=$decision)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            key: SiteReviewKey,
            merchantId: String,
            decision: String,
            reason: String,
            evidenceRef: String?,
            at: Instant,
            by: String,
            requestRecordId: String,
        ) = JpaSiteReviewDecisionEntity().also {
            it.key = key
            it.merchantId = merchantId
            it.decision = decision
            it.reason = reason
            it.evidenceRef = evidenceRef
            it.decidedAt = at
            it.decidedBy = by
            it.requestRecordId = requestRecordId
        }
    }
}

/** (merchant_id, revision, lease_seq) 복합 키. */
@Embeddable
class SnapshotIssueKey protected constructor() : Serializable {
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "revision", nullable = false, updatable = false)
    var revision: Long = 0
        protected set

    @field:Column(name = "lease_seq", nullable = false, updatable = false)
    var leaseSeq: Long = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other is SnapshotIssueKey && merchantId == other.merchantId && revision == other.revision && leaseSeq == other.leaseSeq)

    override fun hashCode(): Int = (31 * merchantId.hashCode() + revision.hashCode()) * 31 + leaseSeq.hashCode()

    override fun toString(): String = "SnapshotIssueKey($merchantId#$revision.$leaseSeq)"

    companion object {
        fun of(merchantId: String, revision: Long, leaseSeq: Long) = SnapshotIssueKey().also {
            it.merchantId = merchantId
            it.revision = revision
            it.leaseSeq = leaseSeq
        }
    }
}

/** entitlement_snapshot_issue 행(append-only 발행 기록). */
@Entity
@Table(name = "entitlement_snapshot_issue")
class JpaSnapshotIssueEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: SnapshotIssueKey
        protected set

    @field:Column(name = "content_hash", nullable = false, updatable = false, length = 80)
    lateinit var contentHash: String
        protected set

    @field:Column(name = "issued_at", nullable = false, updatable = false)
    lateinit var issuedAt: Instant
        protected set

    @field:Column(name = "lease_until", nullable = false, updatable = false)
    lateinit var leaseUntil: Instant
        protected set

    @field:Column(name = "priority", nullable = false, updatable = false, length = 8)
    lateinit var priority: String
        protected set

    @field:Column(name = "outbox_event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var outboxEventId: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaSnapshotIssueEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaSnapshotIssueEntity(key=$key, priority=$priority)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(key: SnapshotIssueKey, contentHash: String, issuedAt: Instant, leaseUntil: Instant, priority: String, eventId: String) =
            JpaSnapshotIssueEntity().also {
                it.key = key
                it.contentHash = contentHash
                it.issuedAt = issuedAt
                it.leaseUntil = leaseUntil
                it.priority = priority
                it.outboxEventId = eventId
            }
    }
}

/** entitlement_snapshot_ack 행(append-only ACK). */
@Entity
@Table(name = "entitlement_snapshot_ack")
class JpaSnapshotAckEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "outbox_event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var outboxEventId: String
        protected set

    @field:Column(name = "consumer", nullable = false, updatable = false, length = 64)
    lateinit var consumer: String
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 24)
    lateinit var outcome: String
        protected set

    @field:Column(name = "applied_revision", nullable = false, updatable = false)
    var appliedRevision: Long = 0
        protected set

    @field:Column(name = "acked_at", nullable = false, updatable = false)
    lateinit var ackedAt: Instant
        protected set

    @field:Column(name = "original_outcome", updatable = false, length = 24)
    var originalOutcome: String? = null
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaSnapshotAckEntity(id=$id, outcome=$outcome)"

    companion object {
        fun newRow(eventId: String, consumer: String, outcome: String, appliedRevision: Long, at: Instant, originalOutcome: String?) = JpaSnapshotAckEntity().also {
            it.originalOutcome = originalOutcome
            it.outboxEventId = eventId
            it.consumer = consumer
            it.outcome = outcome
            it.appliedRevision = appliedRevision
            it.ackedAt = at
        }
    }
}

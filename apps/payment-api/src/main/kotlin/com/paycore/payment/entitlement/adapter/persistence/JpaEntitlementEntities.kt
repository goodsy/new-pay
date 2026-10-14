package com.paycore.payment.entitlement.adapter.persistence

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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant

/*
 * PAYMENT 권리 투영 JPA Entity(adapter 전용 — 순수 domain과 분리). append-only 표(inbox·snapshot·mid_owner)는 모든 컬럼 updatable=false이고
 * Store는 persist만 한다(runtime 계정에 UPDATE 권한 없음 — 1142). head는 잠가 읽은 Entity의 변경 감지 + `@Version`으로 갱신한다.
 * JSON 컬럼은 문자열(`@JdbcTypeCode(SqlTypes.JSON)`)로 매핑한다 — 읽을 때 domain이 구조·hash를 다시 확인한다.
 */

/** inbox_event 행(append-only). */
@Entity
@Table(name = "inbox_event")
class JpaInboxEventEntity protected constructor() {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    @field:Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    @field:Column(name = "consumer", nullable = false, updatable = false, length = 64)
    lateinit var consumer: String
        protected set

    @field:Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "char(36)")
    lateinit var eventId: String
        protected set

    @field:Column(name = "event_type", nullable = false, updatable = false, length = 64)
    lateinit var eventType: String
        protected set

    @field:Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = 0
        protected set

    @field:Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    lateinit var aggregateId: String
        protected set

    @field:Column(name = "aggregate_version", nullable = false, updatable = false)
    var aggregateVersion: Long = 0
        protected set

    @field:Column(name = "outcome", nullable = false, updatable = false, length = 24)
    lateinit var outcome: String
        protected set

    @field:Column(name = "head_revision_after", nullable = false, updatable = false)
    var headRevisionAfter: Long = 0
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "payload", nullable = false, updatable = false)
    lateinit var payload: String
        protected set

    @field:Column(name = "payload_sha256", nullable = false, updatable = false, columnDefinition = "char(64)")
    lateinit var payloadSha256: String
        protected set

    @field:Column(name = "received_at", nullable = false, updatable = false)
    lateinit var receivedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "JpaInboxEventEntity(id=$id, eventId=$eventId, outcome=$outcome)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            consumer: String,
            eventId: String,
            eventType: String,
            schemaVersion: Int,
            aggregateId: String,
            aggregateVersion: Long,
            outcome: String,
            headRevisionAfter: Long,
            payload: String,
            payloadSha256: String,
            receivedAt: Instant,
        ) = JpaInboxEventEntity().also {
            it.consumer = consumer
            it.eventId = eventId
            it.eventType = eventType
            it.schemaVersion = schemaVersion
            it.aggregateId = aggregateId
            it.aggregateVersion = aggregateVersion
            it.outcome = outcome
            it.headRevisionAfter = headRevisionAfter
            it.payload = payload
            it.payloadSha256 = payloadSha256
            it.receivedAt = receivedAt
        }
    }
}

/** entitlement_head 행. 최초 INSERT는 native IODKU(빈 자리), 갱신은 변경 감지 + `@Version`. */
@Entity
@Table(name = "entitlement_head")
class JpaEntitlementHeadEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "revision", nullable = false)
    var revision: Long = 0

    @field:Column(name = "content_hash", length = 80)
    var contentHash: String? = null

    @field:Column(name = "schema_version")
    var schemaVersion: Int? = null

    @field:Column(name = "issued_at")
    var issuedAt: Instant? = null

    @field:Column(name = "lease_seq")
    var leaseSeq: Long? = null

    @field:Column(name = "lease_until")
    var leaseUntil: Instant? = null

    @field:Column(name = "applied_at")
    var appliedAt: Instant? = null

    @field:Column(name = "source_event_id", columnDefinition = "char(36)")
    var sourceEventId: String? = null

    @field:Column(name = "blocked_reason", length = 32)
    var blockedReason: String? = null

    @field:Column(name = "blocked_revision")
    var blockedRevision: Long? = null

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantId == (other as JpaEntitlementHeadEntity).merchantId)

    override fun hashCode(): Int = merchantId.hashCode()

    override fun toString(): String = "JpaEntitlementHeadEntity(merchantId=$merchantId, revision=$revision, blocked=$blockedReason)"
}

/** (merchant_id, revision) 복합 키. */
@Embeddable
class SnapshotKey protected constructor() : Serializable {
    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "revision", nullable = false, updatable = false)
    var revision: Long = 0
        protected set

    override fun equals(other: Any?): Boolean = this === other || (other is SnapshotKey && merchantId == other.merchantId && revision == other.revision)

    override fun hashCode(): Int = 31 * merchantId.hashCode() + revision.hashCode()

    override fun toString(): String = "SnapshotKey($merchantId#$revision)"

    companion object {
        fun of(merchantId: String, revision: Long) = SnapshotKey().also {
            it.merchantId = merchantId
            it.revision = revision
        }
    }
}

/** entitlement_snapshot 행(append-only, revision별 정규 내용). */
@Entity
@Table(name = "entitlement_snapshot")
class JpaEntitlementSnapshotEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: SnapshotKey
        protected set

    @field:Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = 0
        protected set

    @field:Column(name = "content_hash", nullable = false, updatable = false, length = 80)
    lateinit var contentHash: String
        protected set

    @field:JdbcTypeCode(SqlTypes.JSON)
    @field:Column(name = "payload", nullable = false, updatable = false)
    lateinit var payload: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaEntitlementSnapshotEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaEntitlementSnapshotEntity(key=$key)"

    companion object {
        fun newRow(key: SnapshotKey, schemaVersion: Int, contentHash: String, payload: String) = JpaEntitlementSnapshotEntity().also {
            it.key = key
            it.schemaVersion = schemaVersion
            it.contentHash = contentHash
            it.payload = payload
        }
    }
}

/** entitlement_mid_owner 행(append-only — MID 소속 불변). */
@Entity
@Table(name = "entitlement_mid_owner")
class JpaMidOwnerEntity protected constructor() {
    @field:Id
    @field:Column(name = "merchant_account_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantAccountId: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 64)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "recorded_at", nullable = false, updatable = false)
    lateinit var recordedAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && merchantAccountId == (other as JpaMidOwnerEntity).merchantAccountId)

    override fun hashCode(): Int = merchantAccountId.hashCode()

    override fun toString(): String = "JpaMidOwnerEntity(mid=$merchantAccountId, merchantId=$merchantId)"

    companion object {
        fun newRow(mid: String, merchantId: String, at: Instant) = JpaMidOwnerEntity().also {
            it.merchantAccountId = mid
            it.merchantId = merchantId
            it.recordedAt = at
        }
    }
}

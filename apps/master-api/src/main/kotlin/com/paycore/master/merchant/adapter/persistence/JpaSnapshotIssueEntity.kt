package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable
import java.time.Instant

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

    /** MASTER-SNAPSHOT-002 §5.2: 이 발행의 Snapshot schemaVersion. */
    @field:Column(name = "schema_version", nullable = false, updatable = false)
    var schemaVersion: Int = 0
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaSnapshotIssueEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaSnapshotIssueEntity(key=$key, priority=$priority)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(key: SnapshotIssueKey, contentHash: String, issuedAt: Instant, leaseUntil: Instant, priority: String, eventId: String, schemaVersion: Int) =
            JpaSnapshotIssueEntity().also {
                it.key = key
                it.contentHash = contentHash
                it.issuedAt = issuedAt
                it.leaseUntil = leaseUntil
                it.priority = priority
                it.outboxEventId = eventId
                it.schemaVersion = schemaVersion
            }
    }
}

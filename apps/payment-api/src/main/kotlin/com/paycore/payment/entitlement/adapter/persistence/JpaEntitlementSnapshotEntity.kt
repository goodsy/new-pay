package com.paycore.payment.entitlement.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.io.Serializable

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

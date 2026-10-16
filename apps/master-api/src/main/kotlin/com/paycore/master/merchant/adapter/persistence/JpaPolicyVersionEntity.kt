package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

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

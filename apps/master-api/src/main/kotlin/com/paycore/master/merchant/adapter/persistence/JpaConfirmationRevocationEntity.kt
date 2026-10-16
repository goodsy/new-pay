package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

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

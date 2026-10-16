package com.paycore.master.merchant.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.Instant

/** contract_revision_approval 행(append-only 확정 사실, Revision당 하나 — PK). */
@Entity
@Table(name = "contract_revision_approval")
class JpaContractRevisionApprovalEntity protected constructor() {
    @field:EmbeddedId
    lateinit var key: RevisionKey
        protected set

    @field:Column(name = "approved_at", nullable = false, updatable = false)
    lateinit var approvedAt: Instant
        protected set

    @field:Column(name = "approved_by", nullable = false, updatable = false, length = 64)
    lateinit var approvedBy: String
        protected set

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && key == (other as JpaContractRevisionApprovalEntity).key)

    override fun hashCode(): Int = key.hashCode()

    override fun toString(): String = "JpaContractRevisionApprovalEntity(key=$key)"

    companion object {
        fun newRow(key: RevisionKey, at: Instant, by: String) = JpaContractRevisionApprovalEntity().also {
            it.key = key
            it.approvedAt = at
            it.approvedBy = by
        }
    }
}

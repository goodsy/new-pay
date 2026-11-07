package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * admin_acquiring_ops_command 행(ACQ-ADMIN-001 B1-A V7 — 행 모양만, 규칙은 domain `ReacquisitionRequestSnapshot`). INSERT-only: `@Immutable`(Hibernate가 UPDATE를 내지 않는다) +
 * DB 권한 SELECT·INSERT만(1142, V8) — 고정 값(`pinned_target`)·사유를 두 겹으로 막는다. persist만(merge 금지). equals/hashCode는 ID만, toString에 사유·근거·고정 값이 없다.
 * Kotlin data class를 쓰지 않는다(JPA 프록시·equals 의미 — TECHNOLOGY-AND-PRINCIPLES §3).
 */
@Entity
@Immutable
@Table(name = "admin_acquiring_ops_command")
class JpaAcquiringOpsCommandEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 30)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "original_file_id", nullable = false, updatable = false, length = 64)
    lateinit var originalFileId: String
        protected set

    @field:Column(name = "reason_code", nullable = false, updatable = false, length = 32)
    lateinit var reasonCode: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "reason", nullable = false, updatable = false, length = 500)
    lateinit var reason: String
        protected set

    @field:Column(name = "pinned_target", nullable = false, updatable = false, columnDefinition = "json")
    lateinit var pinnedTarget: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && approvalId == (other as JpaAcquiringOpsCommandEntity).approvalId)

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaAcquiringOpsCommandEntity(approvalId=$approvalId)"

    companion object {
        @Suppress("LongParameterList")
        fun newRow(
            approvalId: String, originalFileId: String, reasonCode: String, evidenceRef: String, reason: String, pinnedTarget: String, createdAt: Instant,
        ) = JpaAcquiringOpsCommandEntity().also {
            it.approvalId = approvalId
            it.originalFileId = originalFileId
            it.reasonCode = reasonCode
            it.evidenceRef = evidenceRef
            it.reason = reason
            it.pinnedTarget = pinnedTarget
            it.createdAt = createdAt
        }
    }
}

package com.paycore.admin.approval.adapter.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import org.hibernate.annotations.Immutable
import java.time.Instant

/**
 * admin_merchant_ops_command 행(MERCHANT-OPS-002 B2-2 V9 — 행 모양만, 규칙은 domain `MerchantOpsRequestSnapshot`). INSERT-only: `@Immutable`(Hibernate가 UPDATE를 내지 않는다) +
 * DB 권한 SELECT·INSERT만(1142, V10) — 고정 값(`pinned_target`)·근거 참조를 두 겹으로 막는다. persist만(merge 금지). equals/hashCode는 ID만, toString에 근거·고정 값이 없다.
 * Kotlin data class를 쓰지 않는다(JPA 프록시·equals 의미 — TECHNOLOGY-AND-PRINCIPLES §3).
 */
@Entity
@Immutable
@Table(name = "admin_merchant_ops_command")
class JpaMerchantOpsCommandEntity protected constructor() {
    @field:Id
    @field:Column(name = "approval_id", nullable = false, updatable = false, length = 30)
    lateinit var approvalId: String
        protected set

    @field:Column(name = "command_kind", nullable = false, updatable = false, length = 32)
    lateinit var commandKind: String
        protected set

    @field:Column(name = "merchant_id", nullable = false, updatable = false, length = 30)
    lateinit var merchantId: String
        protected set

    @field:Column(name = "pinned_target", nullable = false, updatable = false, columnDefinition = "json")
    lateinit var pinnedTarget: String
        protected set

    @field:Column(name = "evidence_ref", nullable = false, updatable = false, length = 128)
    lateinit var evidenceRef: String
        protected set

    @field:Column(name = "created_at", nullable = false, updatable = false)
    lateinit var createdAt: Instant
        protected set

    override fun equals(other: Any?): Boolean =
        this === other || (other != null && Hibernate.getClass(this) == Hibernate.getClass(other) && approvalId == (other as JpaMerchantOpsCommandEntity).approvalId)

    override fun hashCode(): Int = approvalId.hashCode()

    override fun toString(): String = "JpaMerchantOpsCommandEntity(approvalId=$approvalId)"

    companion object {
        fun newRow(approvalId: String, commandKind: String, merchantId: String, pinnedTarget: String, evidenceRef: String, createdAt: Instant) =
            JpaMerchantOpsCommandEntity().also {
                it.approvalId = approvalId
                it.commandKind = commandKind
                it.merchantId = merchantId
                it.pinnedTarget = pinnedTarget
                it.evidenceRef = evidenceRef
                it.createdAt = createdAt
            }
    }
}
